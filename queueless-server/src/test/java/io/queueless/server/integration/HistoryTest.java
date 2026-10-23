package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.server.db.Sql;
import io.queueless.server.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import static io.queueless.server.integration.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUEUELESS_TEST_DB_URL",matches=".+")
class HistoryTest {
    static HikariDataSource db;
    @BeforeAll static void start(){db=database();}
    @AfterAll static void stop(){if(db!=null)db.close();}

    @Test void oldUnresolvedVisitsStayVisibleAndEveryHistoricalRowIsReachable() throws Exception {
        var f=fixture(db);
        var queue=new QueueService(db,new ObjectMapper(),Clock.systemUTC());
        var live=queue.execute(f.user(),"history-user",command("JOIN",f.service(),0,0,0,0,Map.of()));
        try(var c=db.getConnection();var insert=c.prepareStatement("""
                INSERT INTO queue_entries(service_id,user_id,business_date,token_number,state,ready_sequence,eligible_at,finished_at)
                SELECT service_id,user_id,business_date,?,'CANCELLED',?,eligible_at,CURRENT_TIMESTAMP(6) FROM queue_entries WHERE id=?
                """)) {
            for(int n=2;n<=206;n++) {insert.setInt(1,n);insert.setInt(2,n);insert.setLong(3,live.entityId());insert.addBatch();}
            insert.executeBatch();
        }
        var query=new QueryService(db,Clock.systemUTC());
        var snapshot=query.snapshot(f.user(),f.service());
        assertTrue(snapshot.visits().stream().anyMatch(v->v.id()==live.entityId() && v.state().equals("WAITING")));
        assertEquals(101,snapshot.visits().size());
        Set<Long> ids=new HashSet<>();long cursor=0;
        do {
            var page=query.history(f.user(),f.service(),cursor,50);
            assertTrue(page.items().size()<=50);
            for(var row:page.items())assertTrue(ids.add(row.id()),"Pages must not repeat records");
            cursor=page.nextBeforeId();
        } while(cursor!=0);
        assertEquals(206,ids.size());
        assertTrue(ids.contains(live.entityId()));
        try(var c=db.getConnection()) {
            var stranger=user(c,"USER");
            assertTrue(query.history(stranger,f.service(),0,50).items().isEmpty());
            var unassigned=user(c,"STAFF");
            assertThrows(AppException.class,()->query.history(unassigned,f.service(),0,50));
        }
        assertThrows(AppException.class,()->query.history(f.user(),f.service(),0,101));
    }

    @Test void appointmentHistoryIsStableAndCustomerScoped() throws Exception {
        var f=fixture(db);
        try(var c=db.getConnection()) {
            long slot=Sql.insert(c,"INSERT INTO appointment_slots(service_id,starts_at,ends_at,capacity,reserved) VALUES(?,CURRENT_TIMESTAMP(6),TIMESTAMPADD(MINUTE,15,CURRENT_TIMESTAMP(6)),10,0)",f.service());
            for(int n=0;n<5;n++)Sql.insert(c,"INSERT INTO appointments(user_id,service_id,slot_id,state) VALUES(?,?,?,'CANCELLED')",f.user().id(),f.service(),slot);
            var other=user(c,"USER");
            Sql.insert(c,"INSERT INTO appointments(user_id,service_id,slot_id,state) VALUES(?,?,?,'CANCELLED')",other.id(),f.service(),slot);
        }
        var query=new QueryService(db,Clock.systemUTC());
        var first=query.appointmentHistory(f.user(),f.service(),0,3);
        var second=query.appointmentHistory(f.user(),f.service(),first.nextBeforeId(),3);
        assertEquals(3,first.items().size());assertEquals(2,second.items().size());assertEquals(0,second.nextBeforeId());
        assertTrue(first.items().getLast().id()>second.items().getFirst().id());
        assertEquals(6,query.appointmentHistory(f.staff(),f.service(),0,10).items().size());
    }
    @Test void unreadInboxItemsCannotDisappearBehindNewerReadItems() throws Exception {
        var f=fixture(db);long unread=0;
        try(var c=db.getConnection()) {
            c.setAutoCommit(false);
            for(int n=0;n<121;n++) {
                long work=Sql.insert(c,"INSERT INTO notification_work(user_id,event_key,message,done) VALUES(?,?,?,TRUE)",f.user().id(),UUID.randomUUID().toString(),"Inbox history "+n);
                long id=Sql.insert(c,"INSERT INTO notifications(user_id,work_id,message,is_read) VALUES(?,?,?,?)",f.user().id(),work,"Inbox history "+n,n!=0);
                if(n==0)unread=id;
            }
            c.commit();
        }
        var snapshot=new QueryService(db,Clock.systemUTC()).snapshot(f.user(),f.service());
        long expected=unread;
        assertEquals(101,snapshot.notifications().size());
        assertTrue(snapshot.notifications().stream().anyMatch(n->n.id()==expected && !n.read()));
    }

    @Test void priorDayVisitBlocksJoinAndAppointmentCheckInUntilResolved() throws Exception {
        var f = fixture(db);
        var date = LocalDate.now(QueueService.ZONE);
        var today = date.atTime(10, 0).atZone(QueueService.ZONE).toInstant();
        var yesterday = date.minusDays(1).atTime(10, 0).atZone(QueueService.ZONE).toInstant();
        var oldQueue = new QueueService(db, new ObjectMapper(), Clock.fixed(yesterday, ZoneOffset.UTC));
        var queue = new QueueService(db, new ObjectMapper(), Clock.fixed(today, ZoneOffset.UTC));
        var oldVisit = oldQueue.execute(f.user(), "yesterday", command("JOIN", f.service(), 0, 0, 0, 0, Map.of()));
        var joinToday = command("JOIN", f.service(), 0, 0, 0, 0, Map.of());
        var duplicate = assertThrows(AppException.class, () -> queue.execute(f.user(), "today", joinToday));
        assertEquals(409, duplicate.status());
        assertTrue(duplicate.getMessage().contains("current visit"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE id=? AND business_date=? AND state='WAITING'",
                oldVisit.entityId(), date.minusDays(1)));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM queue_sessions WHERE service_id=? AND business_date=?", f.service(), date));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?", f.user().id(), joinToday.requestId()));
        assertTrue(new QueryService(db, Clock.fixed(today, ZoneOffset.UTC)).snapshot(f.user(), f.service())
                .visits().stream().anyMatch(v -> v.id() == oldVisit.entityId()));

        long slot = queue.execute(f.admin(), "admin", command("CREATE_SLOT", f.service(), 0, 0, 0, 0,
                Map.of("startsAt", today.plusSeconds(300).toString(), "endsAt", today.plusSeconds(900).toString(), "capacity", "1"))).entityId();
        var booking = queue.execute(f.user(), "today", command("BOOK", f.service(), slot, 0, 0, 0, Map.of()));
        var checkIn = command("CHECK_IN", f.service(), booking.entityId(), 0, booking.version(), 0, Map.of());
        assertEquals(409, assertThrows(AppException.class, () -> queue.execute(f.user(), "today", checkIn)).status());
        assertEquals(1, count(db, "SELECT COUNT(*) FROM appointments WHERE id=? AND state='BOOKED' AND visit_id IS NULL", booking.entityId()));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?", f.user().id(), checkIn.requestId()));

        queue.execute(f.user(), "today", command("CANCEL_VISIT", f.service(), oldVisit.entityId(), 0, oldVisit.version(), 0, Map.of()));
        queue.execute(f.user(), "today", checkIn);
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE service_id=? AND unresolved_user=? AND business_date=? AND token_number=1",
                f.service(), f.user().id(), date));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM appointments WHERE id=? AND state='CHECKED_IN' AND visit_id IS NOT NULL", booking.entityId()));
        assertEquals(1, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", slot));
        assertEquals(2, count(db, "SELECT COUNT(*) FROM queue_entries WHERE service_id=? AND user_id=?", f.service(), f.user().id()));
    }
}
