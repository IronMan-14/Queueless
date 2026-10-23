package io.queueless.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queueless.contracts.Api;
import io.queueless.server.db.Commands;
import io.queueless.server.db.Sql;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static io.queueless.server.service.AppException.require;

/** Application boundary shared by JSP controllers and JSON servlets. */
public final class QueueService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private final DataSource db;
    private final Commands commands;
    private final Clock clock;
    private final Admissions admissions;
    private final AppointmentService appointments;
    private final Administration administration;
    private final Runnable committed;
    public QueueService(DataSource db, ObjectMapper json, Clock clock) {
        this(db, json, clock, () -> { });
    }
    public QueueService(DataSource db, ObjectMapper json, Clock clock, Runnable committed) {
        this.db = db; this.commands = new Commands(db, json); this.clock = clock;
        this.admissions = new Admissions(clock); this.appointments = new AppointmentService(clock, admissions);
        this.administration = new Administration(clock);
        this.committed = committed;
    }
    public Api.Result execute(Api.User actor, String sessionId, Api.Command cmd) throws SQLException {
        if (cmd == null || cmd.operation() == null) throw new AppException(400, "INVALID_INPUT", "An operation is required.");
        if (!Set.of("CREATE_SERVICE", "READ_NOTIFICATION", "READ_NOTIFICATIONS").contains(cmd.operation()) && cmd.serviceId() <= 0)
            throw new AppException(400, "INVALID_INPUT", "Choose a service.");
        if (cmd.fields().size() > 24 || cmd.fields().entrySet().stream().anyMatch(e -> e.getKey().length() > 100 || e.getValue().length() > 4096))
            throw new AppException(400, "INVALID_INPUT", "Command fields exceed the allowed size.");
        if (cmd.fields().getOrDefault("reason", "").length() > 500)
            throw new AppException(400, "INVALID_INPUT", "Reasons must be at most 500 characters.");
        Api.Result result;
        try {
        result = commands.execute(actor.id(), cmd, c -> {
            if (cmd.serviceId() > 0) require(Sql.one(c, "SELECT service_id FROM service_runtime WHERE service_id=? FOR UPDATE", r -> r.getLong(1), cmd.serviceId()).isPresent(), "Service does not exist.");
            var users = new TreeSet<Long>(); users.add(actor.id());
            if (Set.of("CANCEL_VISIT", "REQUEUE").contains(cmd.operation())) {
                long owner = Sql.one(c, "SELECT user_id FROM queue_entries WHERE id=? AND service_id=?", r -> r.getLong(1), cmd.entityId(), cmd.serviceId()).orElseThrow(() -> AppException.conflict("Visit not found."));
                if (owner != actor.id()) staff(c, actor, cmd.serviceId());
            }
            if (Set.of("ASSIGN_STAFF", "UNASSIGN_STAFF").contains(cmd.operation())) {
                try { users.add(Long.parseLong(cmd.fields().getOrDefault("userId", "0"))); }
                catch (NumberFormatException e) { throw new AppException(400, "INVALID_INPUT", "Choose a valid staff account."); }
            }
            if (Set.of("CHECK_IN", "CANCEL_APPOINTMENT", "RESCHEDULE").contains(cmd.operation())) {
                long owner = Sql.one(c, "SELECT user_id FROM appointments WHERE id=? AND service_id=?", r -> r.getLong(1), cmd.entityId(), cmd.serviceId()).orElseThrow(() -> AppException.conflict("Appointment not found."));
                users.add(owner);
                if (owner != actor.id()) staff(c, actor, cmd.serviceId());
            }
            String current = null;
            for (long userId : users) {
                var role = Sql.one(c, "SELECT role FROM users WHERE id=? AND active=TRUE FOR UPDATE", r -> r.getString(1), userId)
                        .orElseThrow(() -> new AppException(403, "FORBIDDEN", "Account unavailable."));
                if (userId == actor.id()) current = role;
            }
            require(current.equals(actor.role()), "Account role changed. Sign in again.");
            if (Administration.OPERATIONS.contains(cmd.operation()) && !actor.role().equals("ADMIN")) throw new AppException(403, "FORBIDDEN", "Administrator access required.");
            if (Set.of("CLAIM","RELEASE","TAKEOVER","PAUSE","NEXT","START","COMPLETE","SKIP","RECALL","ABORT").contains(cmd.operation())) staff(c, actor, cmd.serviceId());
            return null;
        }, c -> switch (cmd.operation()) {
            case "JOIN" -> admissions.join(c, actor.id(), cmd.serviceId(), null);
            case "BOOK", "CHECK_IN", "CANCEL_APPOINTMENT", "RESCHEDULE" -> appointments.execute(c, actor, cmd);
            case "CLAIM", "RELEASE", "TAKEOVER", "PAUSE" -> counter(c, actor, sessionId, cmd);
            case "NEXT" -> next(c, actor, sessionId, cmd);
            case "START", "COMPLETE", "SKIP", "RECALL", "ABORT", "CANCEL_VISIT", "REQUEUE" -> transition(c, actor, sessionId, cmd);
            case "READ_NOTIFICATION" -> {
                require(Sql.update(c, "UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?", cmd.entityId(), actor.id()) == 1, "Notification not found.");
                yield new Api.Result(cmd.entityId(), 0, "Notification marked as read.", false);
            }
            case "READ_NOTIFICATIONS" -> readNotifications(c, actor.id(), cmd.fields().get("notificationIds"));
            default -> {
                if (Administration.OPERATIONS.contains(cmd.operation())) yield administration.execute(c, actor, cmd);
                throw new AppException(400, "INVALID_OPERATION", "Unknown operation.");
            }
        });
        } catch (SQLException failure) {
            // The command helper has rolled back. A domain uniqueness conflict is not an uncertain commit.
            if (failure.getErrorCode() == 1062 && "23000".equals(failure.getSQLState()))
                throw AppException.conflict("A record with these details already exists. Refresh and choose unique details.");
            throw failure;
        }
        committed.run();
        return result;
    }
    /** Exact snapshot IDs, not an ID/time watermark: late commits must stay unread. */
    private Api.Result readNotifications(Connection c, long actor, String supplied) throws SQLException {
        if (supplied == null || !supplied.matches("[1-9][0-9]*(,[1-9][0-9]*){0,99}"))
            throw new AppException(400, "INVALID_INPUT", "Choose between 1 and 100 notifications to mark as read.");
        var ids = new TreeSet<Long>();
        try {
            for (String value : supplied.split(",")) {
                if (!ids.add(Long.parseLong(value))) throw new NumberFormatException("Repeated ID");
            }
        } catch (NumberFormatException invalid) {
            throw new AppException(400, "INVALID_INPUT", "Notification references must be unique positive numbers.");
        }
        // Validate and lock the complete actor-owned set before updating any row. A stale read flag is safe.
        for (long id : ids) require(Sql.one(c, "SELECT id FROM notifications WHERE id=? AND user_id=? FOR UPDATE",
                r -> r.getLong(1), id, actor).isPresent(), "Some notifications are no longer available. Refresh your inbox.");
        int changed = 0;
        for (long id : ids) changed += Sql.update(c, "UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=? AND is_read=FALSE", id, actor);
        String message = changed == 0 ? "Those notifications are already read."
                : changed + (changed == 1 ? " notification marked as read." : " notifications marked as read.");
        return new Api.Result(0, 0, message, false);
    }
    static void staff(Connection c, Api.User actor, long serviceId) throws SQLException {
        if (actor.role().equals("ADMIN")) return;
        if (!actor.role().equals("STAFF") || Sql.one(c, "SELECT user_id FROM staff_assignments WHERE user_id=? AND service_id=?", r -> r.getLong(1), actor.id(), serviceId).isEmpty())
            throw new AppException(403, "FORBIDDEN", "You are not assigned to this service.");
    }
    private record CounterRow(long id, Long owner, String session, long version, boolean paused) { }
    private CounterRow counterRow(Connection c, Api.Command cmd) throws SQLException {
        return Sql.one(c, "SELECT * FROM counters WHERE id=? AND service_id=? FOR UPDATE", r -> new CounterRow(r.getLong("id"), (Long) r.getObject("owner_id"), r.getString("owner_session"), r.getLong("claim_version"), r.getBoolean("paused")), cmd.counterId(), cmd.serviceId())
                .orElseThrow(() -> AppException.conflict("Counter not found in this service."));
    }
    private void ownership(CounterRow row, Api.User actor, String session, Api.Command cmd) {
        require(Objects.equals(row.owner(), actor.id()) && Objects.equals(row.session(), session) && row.version() == cmd.claimVersion(), "Counter ownership changed. Refresh or request an authorized takeover.");
    }
    private Api.Result counter(Connection c, Api.User actor, String session, Api.Command cmd) throws SQLException {
        var row = counterRow(c, cmd);
        switch (cmd.operation()) {
            case "CLAIM" -> {
                require(row.owner() == null || Objects.equals(row.owner(), actor.id()), "Counter is owned by another operator.");
                require(row.version() == cmd.claimVersion(), "Counter claim changed. Refresh before claiming or resuming.");
                Sql.update(c, "UPDATE counters SET owner_id=?,owner_session=?,claim_version=claim_version+1 WHERE id=?", actor.id(), session, row.id());
            }
            case "TAKEOVER" -> {
                if (!actor.role().equals("ADMIN")) throw new AppException(403, "FORBIDDEN", "Only an administrator can take over a counter.");
                require(!cmd.fields().getOrDefault("reason", "").isBlank(), "A takeover reason is required.");
                Sql.update(c, "UPDATE counters SET owner_id=?,owner_session=?,claim_version=claim_version+1 WHERE id=?", actor.id(), session, row.id());
            }
            case "RELEASE" -> {
                ownership(row, actor, session, cmd);
                require(Sql.one(c, "SELECT id FROM queue_entries WHERE occupied_counter=?", r -> r.getLong(1), row.id()).isEmpty(), "Finish or explicitly resolve the current visit before releasing.");
                Sql.update(c, "UPDATE counters SET owner_id=NULL,owner_session=NULL,claim_version=claim_version+1 WHERE id=?", row.id());
            }
            case "PAUSE" -> { ownership(row, actor, session, cmd); Sql.update(c, "UPDATE counters SET paused=? WHERE id=?", Boolean.parseBoolean(cmd.fields().get("paused")), row.id()); }
            default -> throw new IllegalStateException();
        }
        audit(c, actor.id(), cmd, cmd.fields().getOrDefault("reason", ""));
        return new Api.Result(row.id(), row.version() + (cmd.operation().equals("PAUSE") ? 0 : 1), "Counter updated.", false);
    }
    private Api.Result next(Connection c, Api.User actor, String session, Api.Command cmd) throws SQLException {
        var counter = counterRow(c, cmd); ownership(counter, actor, session, cmd);
        require(!counter.paused(), "Counter is paused.");
        require(Sql.one(c, "SELECT id FROM queue_entries WHERE occupied_counter=?", r -> r.getLong(1), counter.id()).isEmpty(), "Counter already has a visit.");
        var candidates = Sql.query(c, "SELECT id,dispatch_class,ready_sequence,appointment_start FROM queue_entries WHERE service_id=? AND state='WAITING' AND eligible_at<=?", r -> new QueuePolicy.Candidate(r.getLong(1), r.getString(2), r.getLong(3), r.getTimestamp(4) == null ? null : r.getTimestamp(4).toInstant()), cmd.serviceId(), clock.instant());
        String policy = Sql.one(c, "SELECT policy FROM services WHERE id=?", r -> r.getString(1), cmd.serviceId()).orElseThrow();
        int streak = Sql.one(c, "SELECT appointment_streak FROM service_runtime WHERE service_id=?", r -> r.getInt(1), cmd.serviceId()).orElseThrow();
        var chosen = QueuePolicy.forName(policy).select(candidates, streak).orElseThrow(() -> AppException.conflict("No eligible visits are waiting."));
        require(Sql.update(c, "UPDATE queue_entries SET state='CALLED',counter_id=?,called_at=?,version=version+1 WHERE id=? AND state='WAITING'", counter.id(), clock.instant(), chosen.id()) == 1, "Queue changed; retry.");
        Sql.update(c, "UPDATE service_runtime SET appointment_streak=?,revision=revision+1 WHERE service_id=?", chosen.dispatchClass().equals("A") ? Math.min(2, streak + 1) : 0, cmd.serviceId());
        event(c, chosen.id(), actor.id(), "CALL", "Counter " + counter.id());
        return result(c, chosen.id(), "Visit called.");
    }
    private record VisitRow(long id, long user, String state, long version, Long counter, int requeues, Instant deadline) { }
    private Api.Result transition(Connection c, Api.User actor, String session, Api.Command cmd) throws SQLException {
        var row = Sql.one(c, "SELECT * FROM queue_entries WHERE id=? AND service_id=? FOR UPDATE", r -> new VisitRow(r.getLong("id"), r.getLong("user_id"), r.getString("state"), r.getLong("version"), (Long)r.getObject("counter_id"), r.getInt("requeue_count"), r.getTimestamp("return_deadline") == null ? null : r.getTimestamp("return_deadline").toInstant()), cmd.entityId(), cmd.serviceId()).orElseThrow(() -> AppException.conflict("Visit not found."));
        require(row.version() == cmd.expectedVersion(), "Visit changed. Refresh before acting.");
        boolean owner = row.user() == actor.id();
        boolean customerAction = Set.of("CANCEL_VISIT", "REQUEUE").contains(cmd.operation());
        if (!customerAction || !owner) staff(c, actor, cmd.serviceId());
        if (!customerAction || row.state().equals("CALLED")) {
            var counter = counterRow(c, cmd); ownership(counter, actor, session, cmd);
            require(Objects.equals(row.counter(), counter.id()), "Visit belongs to another counter.");
        }
        String target;
        switch (cmd.operation()) {
            case "START" -> { require(row.state().equals("CALLED"), "Only called visits can start."); target = "SERVING"; Sql.update(c, "UPDATE queue_entries SET started_at=? WHERE id=?", clock.instant(), row.id()); }
            case "COMPLETE" -> { require(row.state().equals("SERVING"), "Only serving visits can complete."); target = "COMPLETED"; }
            case "ABORT" -> { require(row.state().equals("SERVING"), "Only serving visits can abort."); require(!cmd.fields().getOrDefault("reason", "").isBlank(), "An abort reason is required."); target = "ABORTED"; }
            case "RECALL" -> { require(row.state().equals("CALLED"), "Only called visits can be recalled."); target = "CALLED"; }
            case "SKIP" -> { require(row.state().equals("CALLED"), "Only called visits can be skipped."); target = "SKIPPED"; Sql.update(c, "UPDATE queue_entries SET return_deadline=? WHERE id=?", clock.instant().plusSeconds(900), row.id()); }
            case "CANCEL_VISIT" -> { require(Set.of("WAITING","SKIPPED","CALLED").contains(row.state()), "This visit cannot be cancelled."); require(!row.state().equals("CALLED") || !actor.role().equals("USER"), "Ask staff to cancel a called visit."); target = "CANCELLED"; }
            case "REQUEUE" -> { require(row.state().equals("SKIPPED") && row.requeues() == 0 && row.deadline().isAfter(clock.instant()), "The return window has ended or the visit has already been requeued."); admissions.capacity(c, cmd.serviceId()); target = "WAITING"; Sql.update(c, "UPDATE queue_entries SET dispatch_class='W',ready_sequence=?,eligible_at=?,requeue_count=requeue_count+1,return_deadline=NULL WHERE id=?", admissions.sequence(c, cmd.serviceId()), clock.instant(), row.id()); }
            default -> throw new IllegalStateException();
        }
        boolean terminal = Set.of("COMPLETED","CANCELLED","ABORTED").contains(target);
        Sql.update(c, "UPDATE queue_entries SET state=?,counter_id=?,version=version+1,finished_at=? WHERE id=?", target, Set.of("CALLED","SERVING").contains(target) ? row.counter() : null, terminal ? clock.instant() : null, row.id());
        if (terminal) {
            var linked = Sql.one(c, "SELECT id FROM appointments WHERE visit_id=? AND state='CHECKED_IN'", r -> r.getLong(1), row.id());
            if (linked.isPresent()) {
                String appointmentState = target.equals("COMPLETED") ? "FULFILLED" : target;
                Sql.update(c, "UPDATE appointments SET state=?,version=version+1 WHERE id=?", appointmentState, linked.get());
                Events.appointment(c, linked.get(), actor.id(), appointmentState);
            }
        }
        event(c, row.id(), actor.id(), cmd.operation(), cmd.fields().getOrDefault("reason", ""));
        return result(c, row.id(), "Visit " + target.toLowerCase(Locale.ROOT) + ".");
    }
    private Api.Result result(Connection c, long id, String message) throws SQLException {
        return new Api.Result(id, Sql.one(c, "SELECT version FROM queue_entries WHERE id=?", r -> r.getLong(1), id).orElseThrow(), message, false);
    }
    private void event(Connection c, long visit, long actor, String action, String detail) throws SQLException {
        Events.visit(c, visit, actor, action, detail);
    }
    private void audit(Connection c, long actor, Api.Command cmd, String detail) throws SQLException {
        Sql.update(c, "INSERT INTO audit_events(actor_id,service_id,action,entity_id,detail) VALUES(?,?,?,?,?)", actor, cmd.serviceId(), cmd.operation(), cmd.counterId(), detail);
    }
}
