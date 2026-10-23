package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import io.queueless.server.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.queueless.server.integration.TestSupport.*;

@EnabledIfEnvironmentVariable(named="QUEUELESS_TEST_DB_URL",matches=".+")
class QueueWorkflowTest {
    static HikariDataSource db;
    QueueService queue;
    Fixture f;
    @BeforeAll static void start() { db=database(); }
    @AfterAll static void stop() { if(db!=null) db.close(); }
    @BeforeEach void fixture() throws Exception { f=TestSupport.fixture(db); queue=new QueueService(db,new ObjectMapper(),Clock.systemUTC()); }
    @Test void joinsCallsStartsCompletesAndFencesOldVersions() throws Exception {
        var join=command("JOIN",f.service(),0,0,0,0,Map.of());
        var joined=queue.execute(f.user(),"visitor",join);
        assertTrue(queue.execute(f.user(),"visitor",join).replayed());
        assertThrows(AppException.class,()->queue.execute(f.user(),"visitor",command("JOIN",f.service(),0,0,0,0,Map.of())));
        var claim=queue.execute(f.staff(),"operator",command("CLAIM",f.service(),0,f.counter(),0,0,Map.of()));
        var called=queue.execute(f.staff(),"operator",command("NEXT",f.service(),0,f.counter(),0,claim.version(),Map.of()));
        assertEquals(joined.entityId(),called.entityId());
        assertThrows(AppException.class,()->queue.execute(f.staff(),"operator",command("COMPLETE",f.service(),joined.entityId(),f.counter(),called.version(),claim.version(),Map.of())));
        var started=queue.execute(f.staff(),"operator",command("START",f.service(),joined.entityId(),f.counter(),called.version(),claim.version(),Map.of()));
        assertThrows(AppException.class,()->queue.execute(f.staff(),"operator",command("START",f.service(),joined.entityId(),f.counter(),called.version(),claim.version(),Map.of())));
        queue.execute(f.staff(),"operator",command("COMPLETE",f.service(),joined.entityId(),f.counter(),started.version(),claim.version(),Map.of()));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM queue_entries WHERE id=? AND state='COMPLETED' AND counter_id IS NULL",joined.entityId()));
        assertEquals(0,count(db,"SELECT COUNT(*) FROM command_receipts WHERE status='PENDING'"));
        assertEquals(4,count(db,"SELECT COUNT(*) FROM queue_events WHERE visit_id=?",joined.entityId()));
        var snapshot=new QueryService(db,Clock.systemUTC()).snapshot(f.user(),f.service());
        assertEquals("COMPLETED",snapshot.visits().getFirst().state());
        assertNull(snapshot.visits().getFirst().estimatedMinutes());
    }
    @Test void simultaneousTokenRequestsAllocateDistinctNumbersAndOneVisitPerUser() throws Exception {
        var users=new ArrayList<Api.User>();
        try(var c=db.getConnection()) { for(int i=0;i<12;i++) users.add(user(c,"USER")); }
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(12)) {
            var futures=new ArrayList<Future<Api.Result>>();
            for(var actor:users) futures.add(executor.submit(()->{ start.await(); return queue.execute(actor,"visitor",command("JOIN",f.service(),0,0,0,0,Map.of())); }));
            start.countDown(); for(var result:futures) result.get(15,TimeUnit.SECONDS);
        }
        assertEquals(12,count(db,"SELECT COUNT(DISTINCT token_number) FROM queue_entries WHERE service_id=?",f.service()));
        assertEquals(12,count(db,"SELECT last_token FROM queue_sessions WHERE service_id=?",f.service()));
    }
    @Test void takeoverPreservesOccupancyAndRejectsOldSessionEvenWithFreshVersion() throws Exception {
        var visit=queue.execute(f.user(),"u",command("JOIN",f.service(),0,0,0,0,Map.of()));
        var claim=queue.execute(f.staff(),"old",command("CLAIM",f.service(),0,f.counter(),0,0,Map.of()));
        var call=queue.execute(f.staff(),"old",command("NEXT",f.service(),0,f.counter(),0,claim.version(),Map.of()));
        var takeover=queue.execute(f.admin(),"admin",command("TAKEOVER",f.service(),0,f.counter(),0,claim.version(),Map.of("reason","Disconnected workstation")));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM queue_entries WHERE id=? AND occupied_counter=?",visit.entityId(),f.counter()));
        assertThrows(AppException.class,()->queue.execute(f.staff(),"old",command("START",f.service(),call.entityId(),f.counter(),call.version(),takeover.version(),Map.of())));
        queue.execute(f.admin(),"admin",command("START",f.service(),call.entityId(),f.counter(),call.version(),takeover.version(),Map.of()));
    }

    @Test void independentUsersRacingTheFinalActivePlaceLeaveOneVisitAndCompleteEvidence() throws Exception {
        queue.execute(f.admin(), "admin", command("UPDATE_SERVICE", f.service(), 0, 0, 0, 0,
                Map.of("name", "Capacity race " + f.service(), "description", "Final active place fixture",
                        "policy", "FIFO", "open", "true", "walkInLimit", "1",
                        "plannedMinutes", "480", "referenceMinutes", "10")));
        var users = new ArrayList<Api.User>();
        try (var connection = db.getConnection()) {
            for (int n = 0; n < 8; n++) users.add(user(connection, "USER"));
        }
        var requests = users.stream().map(actor -> command("JOIN", f.service(), 0, 0, 0, 0, Map.of())).toList();
        var ready = new CountDownLatch(users.size());
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<Object>();
        try (var workers = Executors.newFixedThreadPool(users.size())) {
            var futures = new ArrayList<Future<Object>>();
            for (int n = 0; n < users.size(); n++) {
                var actor = users.get(n);
                var request = requests.get(n);
                futures.add(workers.submit(() -> {
                    var independentService = new QueueService(db, new ObjectMapper(), Clock.systemUTC());
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Admission race did not start");
                    try { return independentService.execute(actor, "user-" + actor.id(), request); }
                    catch (AppException failure) { return failure; }
                }));
            }
            try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
            finally { start.countDown(); }
            for (var future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, outcomes.stream().filter(Api.Result.class::isInstance).count());
        for (int n = 0; n < outcomes.size(); n++) {
            var outcome = outcomes.get(n);
            if (outcome instanceof AppException failure) {
                assertEquals(409, failure.status());
                assertTrue(failure.getMessage().contains("capacity"));
            }
            assertEquals(outcome instanceof Api.Result ? 1 : 0,
                    count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?",
                            users.get(n).id(), requests.get(n).requestId()));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND status='PENDING'", users.get(n).id()));
        }
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE service_id=?", f.service()));
        assertEquals(1, count(db, "SELECT SUM(last_token) FROM queue_sessions WHERE service_id=?", f.service()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_events e JOIN queue_entries q ON q.id=e.visit_id WHERE q.service_id=? AND e.action='JOIN'", f.service()));
    }

    @Test void competingStaffCancellationAndRequeueCommitOnlyOneTransition() throws Exception {
        Api.User secondStaff;
        try (var connection = db.getConnection()) {
            secondStaff = user(connection, "STAFF");
            Sql.update(connection, "INSERT INTO staff_assignments(user_id,service_id) VALUES(?,?)", secondStaff.id(), f.service());
        }
        var visit = queue.execute(f.user(), "visitor", command("JOIN", f.service(), 0, 0, 0, 0, Map.of()));
        var claim = queue.execute(f.staff(), "operator", command("CLAIM", f.service(), 0, f.counter(), 0, 0, Map.of()));
        var called = queue.execute(f.staff(), "operator", command("NEXT", f.service(), 0, f.counter(), 0, claim.version(), Map.of()));
        var skipped = queue.execute(f.staff(), "operator", command("SKIP", f.service(), visit.entityId(), f.counter(), called.version(), claim.version(), Map.of()));
        // Both assigned staff can initially resolve a skipped visit; neither may overwrite the other's committed decision.
        var cancel = command("CANCEL_VISIT", f.service(), visit.entityId(), 0, skipped.version(), 0, Map.of("reason", "Customer leaving"));
        var requeue = command("REQUEUE", f.service(), visit.entityId(), 0, skipped.version(), 0, Map.of("reason", "Customer returned"));
        var actors = List.of(f.staff(), secondStaff);
        var requests = List.of(cancel, requeue);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<Object>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Object>>();
            for (int n = 0; n < 2; n++) {
                var actor = actors.get(n);
                var request = requests.get(n);
                futures.add(workers.submit(() -> {
                    var independentService = new QueueService(db, new ObjectMapper(), Clock.systemUTC());
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Staff race did not start");
                    try { return independentService.execute(actor, "operator-" + actor.id(), request); }
                    catch (AppException failure) { return failure; }
                }));
            }
            try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
            finally { start.countDown(); }
            for (var future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, outcomes.stream().filter(Api.Result.class::isInstance).count());
        var loser = (AppException) outcomes.stream().filter(AppException.class::isInstance).findFirst().orElseThrow();
        assertEquals(409, loser.status());
        assertTrue(loser.getMessage().contains("Visit changed"));
        boolean cancelled = outcomes.getFirst() instanceof Api.Result;
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE id=? AND state=? AND version=? AND counter_id IS NULL AND requeue_count=?",
                visit.entityId(), cancelled ? "CANCELLED" : "WAITING", skipped.version() + 1, cancelled ? 0 : 1));
        assertEquals(4, count(db, "SELECT COUNT(*) FROM queue_events WHERE visit_id=?", visit.entityId()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_events WHERE visit_id=? AND action=?", visit.entityId(), cancelled ? "CANCEL_VISIT" : "REQUEUE"));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM queue_events WHERE visit_id=? AND action=?", visit.entityId(), cancelled ? "REQUEUE" : "CANCEL_VISIT"));
        for (int n = 0; n < 2; n++) {
            assertEquals(outcomes.get(n) instanceof Api.Result ? 1 : 0,
                    count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?",
                            actors.get(n).id(), requests.get(n).requestId()));
        }
        assertEquals(0, count(db, "SELECT COUNT(*) FROM queue_entries WHERE occupied_counter=?", f.counter()));
    }
}
