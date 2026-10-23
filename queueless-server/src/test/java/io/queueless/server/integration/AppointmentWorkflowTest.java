package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import io.queueless.server.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.queueless.server.integration.TestSupport.*;

@EnabledIfEnvironmentVariable(named="QUEUELESS_TEST_DB_URL",matches=".+")
class AppointmentWorkflowTest {
    static HikariDataSource db; Fixture f; QueueService queue;
    final Instant now=LocalDate.now(QueueService.ZONE).plusDays(1).atTime(10,0).atZone(QueueService.ZONE).toInstant();
    @BeforeAll static void start(){db=database();}
    @AfterAll static void stop(){if(db!=null)db.close();}
    @BeforeEach void fixture() throws Exception{f=TestSupport.fixture(db);queue=at(now);}
    QueueService at(Instant t){return new QueueService(db,new ObjectMapper(),Clock.fixed(t,ZoneOffset.UTC));}
    long slot(Instant start,int cap) throws Exception {
        return queue.execute(f.admin(),"admin",command("CREATE_SLOT",f.service(),0,0,0,0,Map.of("startsAt",start.toString(),"endsAt",start.plusSeconds(600).toString(),"capacity",""+cap))).entityId();
    }
    Api.Result book(Api.User user,long slot) throws Exception {return queue.execute(user,"user",command("BOOK",f.service(),slot,0,0,0,Map.of()));}
    @Test void finalSlotHasOneWinnerAndCancellationReleasesOnlyItsReservation() throws Exception {
        long slot=slot(now.plusSeconds(300),1); Api.User second;
        try(var c=db.getConnection()){second=user(c,"USER");}
        var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Object>>();
            for(var actor:List.of(f.user(),second)) results.add(executor.submit(()->{gate.await();try{return book(actor,slot);}catch(AppException e){return e;}}));
            gate.countDown(); int success=0; for(var result:results)if(result.get(10,TimeUnit.SECONDS) instanceof Api.Result)success++;
            assertEquals(1,success);
        }
        assertEquals(1,count(db,"SELECT reserved FROM appointment_slots WHERE id=?",slot));
        long id=count(db,"SELECT id FROM appointments WHERE slot_id=?",slot);
        queue.execute(f.admin(),"admin",command("CANCEL_APPOINTMENT",f.service(),id,0,0,0,Map.of()));
        assertEquals(0,count(db,"SELECT reserved FROM appointment_slots WHERE id=?",slot));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM appointments WHERE id=? AND reservation_released=TRUE",id));
    }
    @Test void futureAppointmentsDoNotCountAsLiveVisitsAndCheckInRollsBackIfVisitExists() throws Exception {
        long s1=slot(now.plusSeconds(300),3),s2=slot(now.plusSeconds(1800),2);
        var one=book(f.user(),s1); book(f.user(),s2);
        assertEquals(2,count(db,"SELECT COUNT(*) FROM appointments WHERE user_id=?",f.user().id()));
        var visit=queue.execute(f.user(),"user",command("JOIN",f.service(),0,0,0,0,Map.of()));
        assertThrows(AppException.class,()->queue.execute(f.user(),"user",command("CHECK_IN",f.service(),one.entityId(),0,one.version(),0,Map.of())));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM appointments WHERE id=? AND state='BOOKED' AND visit_id IS NULL",one.entityId()));
        queue.execute(f.user(),"user",command("CANCEL_VISIT",f.service(),visit.entityId(),0,visit.version(),0,Map.of()));
        queue.execute(f.user(),"user",command("CHECK_IN",f.service(),one.entityId(),0,one.version(),0,Map.of()));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM queue_entries WHERE service_id=? AND unresolved_user=?",f.service(),f.user().id()));
    }
    @Test void fulfillmentRetainsSlotInventoryAndTwoCountersShareFairness() throws Exception {
        long slot=slot(now.plusSeconds(300),3);
        var appUsers=new ArrayList<Api.User>();
        try(var c=db.getConnection()){for(int i=0;i<3;i++)appUsers.add(user(c,"USER"));}
        for(var actor:appUsers){var b=book(actor,slot);queue.execute(actor,"user",command("CHECK_IN",f.service(),b.entityId(),0,0,0,Map.of()));}
        var walk=queue.execute(f.user(),"user",command("JOIN",f.service(),0,0,0,0,Map.of()));
        var operator=at(now.plusSeconds(301));
        var c1=operator.execute(f.staff(),"staff",command("CLAIM",f.service(),0,f.counter(),0,0,Map.of()));
        var c2=operator.execute(f.admin(),"admin",command("CLAIM",f.service(),0,f.secondCounter(),0,0,Map.of()));
        var first=operator.execute(f.staff(),"staff",command("NEXT",f.service(),0,f.counter(),0,c1.version(),Map.of()));
        var second=operator.execute(f.admin(),"admin",command("NEXT",f.service(),0,f.secondCounter(),0,c2.version(),Map.of()));
        assertNotEquals(walk.entityId(),first.entityId());assertNotEquals(walk.entityId(),second.entityId());
        var started=operator.execute(f.staff(),"staff",command("START",f.service(),first.entityId(),f.counter(),first.version(),c1.version(),Map.of()));
        operator.execute(f.staff(),"staff",command("COMPLETE",f.service(),first.entityId(),f.counter(),started.version(),c1.version(),Map.of()));
        var third=operator.execute(f.staff(),"staff",command("NEXT",f.service(),0,f.counter(),0,c1.version(),Map.of()));
        assertEquals(walk.entityId(),third.entityId());
        assertEquals(3,count(db,"SELECT reserved FROM appointment_slots WHERE id=?",slot));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM appointments WHERE slot_id=? AND state='FULFILLED' AND reservation_released=FALSE",slot));
        assertThrows(AppException.class,()->operator.execute(f.admin(),"admin",command("UPDATE_SLOT",f.service(),slot,0,0,0,Map.of("capacity","2"))));
    }
    @Test void failedRescheduleLeavesOriginalIntactAndDifferentServicesCannotOverlap() throws Exception {
        long s1=slot(now.plusSeconds(300),2),s2=slot(now.plusSeconds(1800),1);
        var original=book(f.user(),s1); Api.User other;
        try(var c=db.getConnection()){other=user(c,"USER");} book(other,s2);
        assertThrows(AppException.class,()->queue.execute(f.user(),"user",command("RESCHEDULE",f.service(),original.entityId(),0,0,0,Map.of("slotId",""+s2))));
        assertEquals(s1,count(db,"SELECT slot_id FROM appointments WHERE id=?",original.entityId()));
        assertEquals(1,count(db,"SELECT reserved FROM appointment_slots WHERE id=?",s1));
        var otherService=TestSupport.fixture(db); long overlapping;
        try(var c=db.getConnection()){overlapping=Sql.insert(c,"INSERT INTO appointment_slots(service_id,starts_at,ends_at,capacity) VALUES(?,?,?,3)",otherService.service(),now.plusSeconds(400),now.plusSeconds(800));}
        assertThrows(AppException.class,()->queue.execute(f.user(),"user",command("BOOK",otherService.service(),overlapping,0,0,0,Map.of())));
        assertEquals(0,count(db,"SELECT reserved FROM appointment_slots WHERE id=?",overlapping));
    }

    @Test void concurrentBookingsForOneUserAcrossServicesCannotCommitOverlappingWindows() throws Exception {
        var other = TestSupport.fixture(db);
        long firstSlot = slot(now.plusSeconds(300), 2);
        long secondSlot = queue.execute(other.admin(), "other-admin",
                command("CREATE_SLOT", other.service(), 0, 0, 0, 0, Map.of(
                        "startsAt", now.plusSeconds(600).toString(),
                        "endsAt", now.plusSeconds(1200).toString(), "capacity", "2"))).entityId();
        var first = command("BOOK", f.service(), firstSlot, 0, 0, 0, Map.of());
        var second = command("BOOK", other.service(), secondSlot, 0, 0, 0, Map.of());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<Object>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Object>>();
            for (var request : List.of(first, second)) {
                futures.add(workers.submit(() -> {
                    // Different service roots cannot serialize this race; the shared customer lock must.
                    var independentService = at(now);
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Booking race did not start");
                    try { return independentService.execute(f.user(), "user-" + request.serviceId(), request); }
                    catch (AppException failure) { return failure; }
                }));
            }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally { start.countDown(); }
            for (var future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, outcomes.stream().filter(Api.Result.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(AppException.class::isInstance).count());
        var rejected = (AppException) outcomes.stream().filter(AppException.class::isInstance).findFirst().orElseThrow();
        assertEquals(409, rejected.status());
        assertTrue(rejected.getMessage().contains("overlaps"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM appointments WHERE user_id=?", f.user().id()));
        assertEquals(1, count(db, "SELECT SUM(reserved) FROM appointment_slots WHERE id IN (?,?)", firstSlot, secondSlot));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id IN (?,?) AND status='DONE'",
                f.user().id(), first.requestId(), second.requestId()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM appointment_events e JOIN appointments a ON a.id=e.appointment_id WHERE a.user_id=? AND e.action='BOOKED'", f.user().id()));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND status='PENDING'", f.user().id()));
    }

    @Test void adjacentHalfOpenWindowsAcrossServicesBothReserveSuccessfully() throws Exception {
        var other = TestSupport.fixture(db);
        long firstSlot = slot(now.plusSeconds(300), 1);
        // The second starts at the exact instant the first ends: [start, end), not closed intervals.
        long secondSlot = queue.execute(other.admin(), "other-admin",
                command("CREATE_SLOT", other.service(), 0, 0, 0, 0, Map.of(
                        "startsAt", now.plusSeconds(900).toString(),
                        "endsAt", now.plusSeconds(1500).toString(), "capacity", "1"))).entityId();
        var first = book(f.user(), firstSlot);
        var second = queue.execute(f.user(), "user", command("BOOK", other.service(), secondSlot, 0, 0, 0, Map.of()));
        assertNotEquals(first.entityId(), second.entityId());
        assertEquals(2, count(db, "SELECT COUNT(*) FROM appointments WHERE user_id=? AND state='BOOKED'", f.user().id()));
        assertEquals(2, count(db, "SELECT SUM(reserved) FROM appointment_slots WHERE id IN (?,?)", firstSlot, secondSlot));
        assertEquals(2, count(db, "SELECT COUNT(*) FROM appointment_events e JOIN appointments a ON a.id=e.appointment_id WHERE a.user_id=? AND e.action='BOOKED'", f.user().id()));
    }
}
