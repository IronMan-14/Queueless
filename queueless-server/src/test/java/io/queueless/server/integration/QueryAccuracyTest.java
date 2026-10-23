package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import io.queueless.server.service.AppException;
import io.queueless.server.service.QueryService;
import io.queueless.server.service.QueueService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.queueless.server.integration.TestSupport.command;
import static io.queueless.server.integration.TestSupport.database;
import static io.queueless.server.integration.TestSupport.user;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "QUEUELESS_TEST_DB_URL", matches = ".+")
class QueryAccuracyTest {
    private static HikariDataSource db;
    private TestSupport.Fixture f;
    private QueueService queue;
    private QueryService query;
    private final Instant now = LocalDate.now(QueueService.ZONE).plusDays(1).atTime(10, 0)
            .atZone(QueueService.ZONE).toInstant();

    @BeforeAll static void start() { db = database(); }
    @AfterAll static void stop() { if (db != null) db.close(); }
    @BeforeEach void fixture() throws Exception {
        f = TestSupport.fixture(db);
        queue = at(now);
        query = new QueryService(db, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void customerAndStaffAvailabilityReflectsCurrentCalendarButAdminRetainsInventory() throws Exception {
        QueueService publishing = at(now.minusSeconds(1800));
        long started = slot(publishing, now.minusSeconds(60), 1);
        long available = slot(publishing, now.plusSeconds(600), 1);
        long full = slot(publishing, now.plusSeconds(1200), 1);
        long outsideHours = slot(publishing, now.plusSeconds(7200), 1);
        long closedDay = slot(publishing, now.plusSeconds(86400 + 600), 1);
        queue.execute(f.user(), "user", command("BOOK", f.service(), full, 0, 0, 0, Map.of()));
        LocalDate date = now.atZone(QueueService.ZONE).toLocalDate();
        queue.execute(f.admin(), "admin", command("HOURS", f.service(), 0, 0, 0, 0,
                Map.of("day", Integer.toString(date.getDayOfWeek().getValue()), "opens", "09:00", "closes", "11:00")));
        queue.execute(f.admin(), "admin", command("CLOSE_DATE", f.service(), 0, 0, 0, 0,
                Map.of("date", date.plusDays(1).toString(), "reason", "Closed for maintenance")));

        assertEquals(Set.of(available), slots(f.user()));
        assertEquals(Set.of(available), slots(f.staff()));
        assertEquals(Set.of(started, available, full, outsideHours, closedDay), slots(f.admin()));
        assertEquals(1, query.snapshot(f.user(), f.service()).appointments().size(), "A full window must not hide the customer's booking");

        queue.execute(f.admin(), "admin", command("UPDATE_SERVICE", f.service(), 0, 0, 0, 0,
                Map.of("name", "Paused " + f.service(), "description", "Paused test service", "policy", "APPOINTMENT_AWARE",
                        "open", "false", "walkInLimit", "100", "plannedMinutes", "480", "referenceMinutes", "10")));
        assertTrue(slots(f.user()).isEmpty());
        assertTrue(slots(f.staff()).isEmpty());
        assertEquals(5, slots(f.admin()).size());
    }

    @Test
    void estimatesUseAvailableCountersAndCompletedServiceWaves() throws Exception {
        for (int n = 0; n < 5; n++) terminalVisit(f, now.minusSeconds(7200 + n * 900L), "COMPLETED");
        Api.Result firstClaim = queue.execute(f.staff(), "staff", command("CLAIM", f.service(), 0, f.counter(), 0, 0, Map.of()));
        Api.Result secondClaim = queue.execute(f.admin(), "admin", command("CLAIM", f.service(), 0, f.secondCounter(), 0, 0, Map.of()));
        Api.Result first = join(f.user());
        assertEstimate(f.user(), first.entityId(), 1, 0);
        queue.execute(f.staff(), "staff", command("NEXT", f.service(), 0, f.counter(), 0, firstClaim.version(), Map.of()));
        Api.User second = anotherUser();
        Api.Result secondVisit = join(second);
        assertEstimate(second, secondVisit.entityId(), 1, 0);
        queue.execute(f.admin(), "admin", command("NEXT", f.service(), 0, f.secondCounter(), 0, secondClaim.version(), Map.of()));

        Api.User third = anotherUser();
        Api.Result thirdVisit = join(third);
        Api.User fourth = anotherUser();
        Api.Result fourthVisit = join(fourth);
        Api.User fifth = anotherUser();
        Api.Result fifthVisit = join(fifth);
        assertEstimate(third, thirdVisit.entityId(), 1, 10);
        assertEstimate(fourth, fourthVisit.entityId(), 2, 10);
        assertEstimate(fifth, fifthVisit.entityId(), 3, 20);
    }

    @Test
    void estimatesStayUnknownWithoutEnoughSamplesOrAnActiveCounter() throws Exception {
        for (int n = 0; n < 4; n++) terminalVisit(f, now.minusSeconds(7200 + n * 900L), "COMPLETED");
        Api.Result claim = queue.execute(f.staff(), "staff", command("CLAIM", f.service(), 0, f.counter(), 0, 0, Map.of()));
        Api.Result visit = join(f.user());
        assertNull(visit(f.user(), visit.entityId()).estimatedMinutes());
        terminalVisit(f, now.minusSeconds(14400), "COMPLETED");
        assertEstimate(f.user(), visit.entityId(), 1, 0);
        queue.execute(f.staff(), "staff", command("RELEASE", f.service(), 0, f.counter(), 0, claim.version(), Map.of()));
        assertNull(visit(f.user(), visit.entityId()).estimatedMinutes());
    }

    @Test
    void activityMatrixUsesLocalServiceStartAndEnforcesDateServiceAndRoleBoundaries() throws Exception {
        LocalDate monday = now.atZone(QueueService.ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        LocalDate sunday = monday.plusDays(6);
        terminalVisit(f, local(monday, 0, 15), "COMPLETED");
        terminalVisit(f, local(monday, 23, 55), "COMPLETED");
        terminalVisit(f, local(sunday, 23, 55), "COMPLETED");
        terminalVisit(f, local(monday.plusDays(1), 1, 0), "CANCELLED");
        terminalVisit(f, local(monday.minusDays(1), 23, 55), "COMPLETED");
        terminalVisit(f, local(sunday.plusDays(1), 0, 0), "COMPLETED");
        TestSupport.Fixture otherService = TestSupport.fixture(db);
        terminalVisit(otherService, local(monday, 0, 15), "COMPLETED");

        long[][] expected = new long[7][24];
        expected[0][0] = 1;
        expected[0][23] = 1;
        expected[6][23] = 1;
        Api.ActivityReport result = query.activity(f.staff(), f.service(), monday, sunday);
        assertEquals(monday.toString(), result.from());
        assertEquals(sunday.toString(), result.to());
        for (int day = 0; day < 7; day++) assertArrayEquals(expected[day], result.completedByStartHour()[day]);
        assertEquals(3, Arrays.stream(result.completedByStartHour()).flatMapToLong(Arrays::stream).sum());
        assertEquals(0, Arrays.stream(query.activity(f.admin(), f.service(), sunday.plusDays(2), sunday.plusDays(2))
                .completedByStartHour()).flatMapToLong(Arrays::stream).sum());
        assertEquals(403, assertThrows(AppException.class, () -> query.activity(f.user(), f.service(), monday, sunday)).status());
        assertEquals(403, assertThrows(AppException.class, () -> query.activity(f.staff(), otherService.service(), monday, sunday)).status());
    }

    private QueueService at(Instant instant) { return new QueueService(db, new ObjectMapper(), Clock.fixed(instant, ZoneOffset.UTC)); }
    private long slot(QueueService publishing, Instant starts, int capacity) throws Exception {
        return publishing.execute(f.admin(), "admin", command("CREATE_SLOT", f.service(), 0, 0, 0, 0,
                Map.of("startsAt", starts.toString(), "endsAt", starts.plusSeconds(600).toString(), "capacity", Integer.toString(capacity)))).entityId();
    }
    private Set<Long> slots(Api.User actor) throws Exception {
        return query.snapshot(actor, f.service()).slots().stream().map(Api.Slot::id).collect(Collectors.toSet());
    }
    private Api.User anotherUser() throws Exception {
        try (Connection c = db.getConnection()) { return user(c, "USER"); }
    }
    private Api.Result join(Api.User actor) throws Exception {
        return queue.execute(actor, "user-" + actor.id(), command("JOIN", f.service(), 0, 0, 0, 0, Map.of()));
    }
    private Api.Visit visit(Api.User actor, long id) throws Exception {
        return query.snapshot(actor, f.service()).visits().stream().filter(v -> v.id() == id).findFirst().orElseThrow();
    }
    private void assertEstimate(Api.User actor, long id, int position, int minutes) throws Exception {
        Api.Visit value = visit(actor, id);
        assertEquals(position, value.position());
        assertEquals(minutes, value.estimatedMinutes());
    }
    private static Instant local(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(QueueService.ZONE).toInstant();
    }
    private void terminalVisit(TestSupport.Fixture fixture, Instant started, String state) throws Exception {
        LocalDate businessDate = started.atZone(QueueService.ZONE).toLocalDate();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                Sql.update(c, "INSERT INTO queue_sessions(service_id,business_date,last_token) VALUES(?,?,1) ON DUPLICATE KEY UPDATE last_token=last_token+1", fixture.service(), businessDate);
                int token = Sql.one(c, "SELECT last_token FROM queue_sessions WHERE service_id=? AND business_date=?", r -> r.getInt(1), fixture.service(), businessDate).orElseThrow();
                Sql.update(c, "UPDATE service_runtime SET ready_sequence=ready_sequence+1 WHERE service_id=?", fixture.service());
                long sequence = Sql.one(c, "SELECT ready_sequence FROM service_runtime WHERE service_id=?", r -> r.getLong(1), fixture.service()).orElseThrow();
                Sql.insert(c, "INSERT INTO queue_entries(service_id,user_id,business_date,token_number,state,ready_sequence,eligible_at,created_at,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        fixture.service(), fixture.user().id(), businessDate, token, state, sequence, started.minusSeconds(300), started.minusSeconds(300), started, started.plusSeconds(600));
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
}
