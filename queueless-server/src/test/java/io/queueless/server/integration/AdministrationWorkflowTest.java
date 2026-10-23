package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.service.AppException;
import io.queueless.server.service.QueueService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static io.queueless.server.integration.TestSupport.command;
import static io.queueless.server.integration.TestSupport.count;
import static io.queueless.server.integration.TestSupport.database;
import static io.queueless.server.integration.TestSupport.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "QUEUELESS_TEST_DB_URL", matches = ".+")
class AdministrationWorkflowTest {
    private static HikariDataSource db;
    private TestSupport.Fixture f;
    private QueueService queue;
    private final Instant now = LocalDate.now(QueueService.ZONE).plusDays(1).atTime(10, 0)
            .atZone(QueueService.ZONE).toInstant();

    @BeforeAll static void start() { db = database(); }
    @AfterAll static void stop() { if (db != null) db.close(); }
    @BeforeEach void fixture() throws Exception { f = TestSupport.fixture(db); queue = at(now); }

    @Test
    void adminAuthorizationAndReceiptReplayProtectConfigurationAndAudit() throws Exception {
        Api.Command create = command("CREATE_COUNTER", f.service(), 0, 0, 0, 0, Map.of("name", "Third"));
        AppException denied = assertThrows(AppException.class, () -> queue.execute(f.staff(), "staff", create));
        assertEquals(403, denied.status());
        assertEquals(0, receiptCount(f.staff(), create));
        assertEquals(2, count(db, "SELECT COUNT(*) FROM counters WHERE service_id=?", f.service()));

        Api.Result created = queue.execute(f.admin(), "admin", create);
        Api.Result replay = queue.execute(f.admin(), "admin", create);
        assertFalse(created.replayed());
        assertTrue(replay.replayed());
        assertEquals(created.entityId(), replay.entityId());
        assertEquals(3, count(db, "SELECT COUNT(*) FROM counters WHERE service_id=?", f.service()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM audit_events WHERE service_id=? AND action='CREATE_COUNTER' AND entity_id=?",
                f.service(), created.entityId()));
        assertEquals(1, receiptCount(f.admin(), create));
    }

    @Test
    void duplicateServiceNameIsConflictWithoutPartialConfigurationOrAReceipt() throws Exception {
        String name = "Unique service " + f.service();
        Map<String, String> fields = Map.of("name", name, "description", "Service uniqueness regression");
        Api.Command original = command("CREATE_SERVICE", 0, 0, 0, 0, 0, fields);
        Api.Result created = queue.execute(f.admin(), "admin", original);
        Api.Command duplicate = command("CREATE_SERVICE", 0, 0, 0, 0, 0, fields);

        assertEquals(409, assertThrows(AppException.class, () -> queue.execute(f.admin(), "admin", duplicate)).status());
        assertEquals(0, receiptCount(f.admin(), duplicate));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM services WHERE name=?", name));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM service_runtime WHERE service_id=?", created.entityId()));
        assertEquals(5, count(db, "SELECT COUNT(*) FROM service_hours WHERE service_id=?", created.entityId()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM audit_events WHERE actor_id=? AND action='CREATE_SERVICE'", f.admin().id()));

        Api.Result replay = queue.execute(f.admin(), "admin", original);
        assertTrue(replay.replayed());
        assertEquals(created.entityId(), replay.entityId());
        assertEquals(created.version(), replay.version());
        assertEquals(1, receiptCount(f.admin(), original));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM audit_events WHERE actor_id=? AND action='CREATE_SERVICE'", f.admin().id()));
    }

    @Test
    void duplicateCounterNameIsConflictWithoutPartialEffectsOrAReceipt() throws Exception {
        Api.Command original = command("CREATE_COUNTER", f.service(), 0, 0, 0, 0, Map.of("name", "Third"));
        Api.Result created = queue.execute(f.admin(), "admin", original);
        Api.Command duplicate = command("CREATE_COUNTER", f.service(), 0, 0, 0, 0, Map.of("name", "Third"));

        assertEquals(409, assertThrows(AppException.class, () -> queue.execute(f.admin(), "admin", duplicate)).status());
        assertEquals(0, receiptCount(f.admin(), duplicate));
        assertEquals(3, count(db, "SELECT COUNT(*) FROM counters WHERE service_id=?", f.service()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM counters WHERE service_id=? AND name='Third'", f.service()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM audit_events WHERE service_id=? AND action='CREATE_COUNTER'", f.service()));

        Api.Result replay = queue.execute(f.admin(), "admin", original);
        assertTrue(replay.replayed());
        assertEquals(created.entityId(), replay.entityId());
        assertEquals(created.version(), replay.version());
        assertEquals(1, receiptCount(f.admin(), original));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM audit_events WHERE service_id=? AND action='CREATE_COUNTER'", f.service()));
    }

    @Test
    void publishingOverPlanRequiresAcknowledgmentAndQuotaCannotFallBelowReservations() throws Exception {
        Instant start = now.plusSeconds(3600);
        Api.Command overPlan = command("CREATE_SLOT", f.service(), 0, 0, 0, 0,
                Map.of("startsAt", start.toString(), "endsAt", start.plusSeconds(600).toString(), "capacity", "49"));
        assertThrows(AppException.class, () -> queue.execute(f.admin(), "admin", overPlan));
        assertEquals(0, receiptCount(f.admin(), overPlan));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM appointment_slots WHERE service_id=?", f.service()));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM audit_events WHERE service_id=? AND action='CREATE_SLOT'", f.service()));

        long slot = queue.execute(f.admin(), "admin", command("CREATE_SLOT", f.service(), 0, 0, 0, 0,
                Map.of("startsAt", start.toString(), "endsAt", start.plusSeconds(600).toString(),
                        "capacity", "49", "acknowledgeShortfall", "true"))).entityId();
        Api.User second;
        try (Connection connection = db.getConnection()) { second = user(connection, "USER"); }
        Api.Result firstBooking = queue.execute(f.user(), "user", command("BOOK", f.service(), slot, 0, 0, 0, Map.of()));
        queue.execute(second, "second", command("BOOK", f.service(), slot, 0, 0, 0, Map.of()));

        Api.Command belowReserved = command("UPDATE_SLOT", f.service(), slot, 0, 0, 0, Map.of("capacity", "1"));
        assertThrows(AppException.class, () -> queue.execute(f.admin(), "admin", belowReserved));
        assertEquals(0, receiptCount(f.admin(), belowReserved));
        assertEquals(49, count(db, "SELECT capacity FROM appointment_slots WHERE id=?", slot));
        assertEquals(2, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", slot));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM audit_events WHERE service_id=? AND action='UPDATE_SLOT'", f.service()));

        queue.execute(f.admin(), "admin", command("UPDATE_SLOT", f.service(), slot, 0, 0, 0, Map.of("capacity", "2")));
        queue.execute(f.user(), "user", command("CANCEL_APPOINTMENT", f.service(), firstBooking.entityId(), 0,
                firstBooking.version(), 0, Map.of()));
        queue.execute(f.admin(), "admin", belowReserved);
        assertEquals(1, count(db, "SELECT capacity FROM appointment_slots WHERE id=?", slot));
        assertEquals(1, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", slot));
        assertEquals(1, receiptCount(f.admin(), belowReserved), "A rejected command must not leave a committed receipt");
    }

    @Test
    void calendarChangesRejectNewBookingsButPreserveExistingArrivalEntitlements() throws Exception {
        long early = slot(now.plusSeconds(300), 2);
        long later = slot(now.plusSeconds(3600), 2);
        Api.User laterUser;
        try (Connection connection = db.getConnection()) { laterUser = user(connection, "USER"); }
        Api.Result earlyBooking = queue.execute(f.user(), "user", command("BOOK", f.service(), early, 0, 0, 0, Map.of()));
        Api.Result laterBooking = queue.execute(laterUser, "later", command("BOOK", f.service(), later, 0, 0, 0, Map.of()));

        LocalDate date = now.atZone(QueueService.ZONE).toLocalDate();
        queue.execute(f.admin(), "admin", command("HOURS", f.service(), 0, 0, 0, 0,
                Map.of("day", Integer.toString(date.getDayOfWeek().getValue()), "opens", "11:00", "closes", "12:00")));
        Api.Command outsideHours = command("BOOK", f.service(), early, 0, 0, 0, Map.of());
        assertThrows(AppException.class, () -> queue.execute(laterUser, "later", outsideHours));
        assertEquals(0, receiptCount(laterUser, outsideHours));
        queue.execute(f.user(), "user", command("CHECK_IN", f.service(), earlyBooking.entityId(), 0,
                earlyBooking.version(), 0, Map.of()));

        Api.Command closeDate = command("CLOSE_DATE", f.service(), 0, 0, 0, 0,
                Map.of("date", date.toString(), "reason", "Maintenance disruption"));
        queue.execute(f.admin(), "admin", closeDate);
        Api.Command closedDate = command("BOOK", f.service(), later, 0, 0, 0, Map.of());
        assertThrows(AppException.class, () -> queue.execute(f.user(), "user", closedDate));
        assertEquals(0, receiptCount(f.user(), closedDate));
        at(now.plusSeconds(3300)).execute(laterUser, "later", command("CHECK_IN", f.service(), laterBooking.entityId(),
                0, laterBooking.version(), 0, Map.of()));

        assertEquals(2, count(db, "SELECT COUNT(*) FROM appointments WHERE service_id=? AND state='CHECKED_IN' AND visit_id IS NOT NULL", f.service()));
        assertEquals(2, count(db, "SELECT COUNT(*) FROM queue_entries WHERE service_id=? AND source='APPOINTMENT' AND state='WAITING'", f.service()));
        assertEquals(1, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", early));
        assertEquals(1, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", later));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM appointments WHERE id=? AND disruption_protected=TRUE", laterBooking.entityId()));
        assertEquals(2, count(db, "SELECT COUNT(*) FROM notification_work WHERE event_key LIKE ?",
                "closure:%:" + closeDate.requestId()));
    }

    @Test
    void staffRemovalRequiresResolvingAndReleasingTheClaimedCounter() throws Exception {
        Api.User ordinary;
        try (Connection connection = db.getConnection()) { ordinary = user(connection, "USER"); }
        Api.Command assign = command("ASSIGN_STAFF", f.service(), 0, 0, 0, 0,
                Map.of("userId", Long.toString(ordinary.id())));
        queue.execute(f.admin(), "admin", assign);
        assertEquals(1, count(db, "SELECT COUNT(*) FROM users WHERE id=? AND role='STAFF'", ordinary.id()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM staff_assignments WHERE user_id=? AND service_id=?", ordinary.id(), f.service()));
        Api.User assigned = new Api.User(ordinary.id(), ordinary.name(), "STAFF");
        Api.Result visit = queue.execute(f.user(), "user", command("JOIN", f.service(), 0, 0, 0, 0, Map.of()));
        Api.Result claim = queue.execute(assigned, "new-staff", command("CLAIM", f.service(), 0, f.counter(), 0, 0, Map.of()));
        Api.Result called = queue.execute(assigned, "new-staff", command("NEXT", f.service(), 0, f.counter(), 0, claim.version(), Map.of()));
        assertEquals(visit.entityId(), called.entityId());

        Api.Command remove = command("UNASSIGN_STAFF", f.service(), 0, 0, 0, 0,
                Map.of("userId", Long.toString(assigned.id())));
        assertThrows(AppException.class, () -> queue.execute(f.admin(), "admin", remove));
        assertEquals(0, receiptCount(f.admin(), remove));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM staff_assignments WHERE user_id=? AND service_id=?", assigned.id(), f.service()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE id=? AND occupied_counter=?", visit.entityId(), f.counter()));

        Api.Result started = queue.execute(assigned, "new-staff", command("START", f.service(), visit.entityId(), f.counter(), called.version(), claim.version(), Map.of()));
        queue.execute(assigned, "new-staff", command("COMPLETE", f.service(), visit.entityId(), f.counter(), started.version(), claim.version(), Map.of()));
        queue.execute(assigned, "new-staff", command("RELEASE", f.service(), 0, f.counter(), 0, claim.version(), Map.of()));
        queue.execute(f.admin(), "admin", remove);
        assertEquals(0, count(db, "SELECT COUNT(*) FROM staff_assignments WHERE user_id=? AND service_id=?", assigned.id(), f.service()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE id=? AND state='COMPLETED'", visit.entityId()));
        Api.Command reclaim = command("CLAIM", f.service(), 0, f.counter(), 0, claim.version() + 1, Map.of());
        assertEquals(403, assertThrows(AppException.class, () -> queue.execute(assigned, "new-staff", reclaim)).status());
        assertEquals(0, receiptCount(assigned, reclaim));
    }

    private QueueService at(Instant instant) { return new QueueService(db, new ObjectMapper(), Clock.fixed(instant, ZoneOffset.UTC)); }
    private long slot(Instant start, int capacity) throws Exception {
        return queue.execute(f.admin(), "admin", command("CREATE_SLOT", f.service(), 0, 0, 0, 0,
                Map.of("startsAt", start.toString(), "endsAt", start.plusSeconds(600).toString(),
                        "capacity", Integer.toString(capacity)))).entityId();
    }
    private long receiptCount(Api.User actor, Api.Command command) throws Exception {
        return count(db, "SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?", actor.id(), command.requestId());
    }
}
