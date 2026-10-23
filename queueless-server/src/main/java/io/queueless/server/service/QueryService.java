package io.queueless.server.service;

import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read models for both clients. No identity, password or other visitor data is exposed publicly. */
public final class QueryService {
    private final DataSource db;
    private final Clock clock;
    public QueryService(DataSource db, Clock clock) { this.db = db; this.clock = clock; }
    public Api.Snapshot snapshot(Api.User actor, long requestedService) throws SQLException {
        try (var c = db.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                var services = Sql.query(c, "SELECT * FROM services WHERE ?<>'STAFF' OR id IN (SELECT service_id FROM staff_assignments WHERE user_id=?) ORDER BY id",
                        r -> new Api.Service(r.getLong("id"), r.getString("name"), r.getString("description"), r.getString("policy"), r.getBoolean("open"), r.getInt("walk_in_limit"), r.getInt("planned_minutes"), r.getInt("reference_minutes")), actor.role(), actor.id());
                long service = requestedService > 0 ? requestedService : services.isEmpty() ? 0 : services.getFirst().id();
                if (service > 0 && services.stream().noneMatch(s -> s.id() == service)) throw new AppException(403, "FORBIDDEN", "Service unavailable to this account.");
                var counters = actor.role().equals("USER") ? List.<Api.Counter>of() : Sql.query(c,
                        "SELECT c.*,q.id visit_id,q.state visit_state FROM counters c LEFT JOIN queue_entries q ON q.occupied_counter=c.id WHERE c.service_id=? ORDER BY c.id",
                        r -> new Api.Counter(r.getLong("id"), r.getLong("service_id"), r.getString("name"), nullableLong(r, "owner_id"), r.getLong("claim_version"), r.getBoolean("paused"), nullableLong(r,"visit_id"), r.getString("visit_state")), service);
                Instant now = clock.instant();
                var candidates = new ArrayList<>(Sql.query(c, "SELECT id,dispatch_class,ready_sequence,appointment_start FROM queue_entries WHERE service_id=? AND state='WAITING' AND eligible_at<=?",
                        r -> new QueuePolicy.Candidate(r.getLong(1), r.getString(2), r.getLong(3), instant(r,4)), service, now));
                var positions = new HashMap<Long,Integer>();
                int streak = Sql.one(c, "SELECT appointment_streak FROM service_runtime WHERE service_id=?", r -> r.getInt(1), service).orElse(0);
                QueuePolicy policy = QueuePolicy.forName(services.stream().filter(s -> s.id()==service).map(Api.Service::policy).findFirst().orElse("FIFO"));
                while (!candidates.isEmpty()) {
                    var next = policy.select(candidates, streak).orElseThrow();
                    positions.put(next.id(), positions.size()+1); candidates.remove(next);
                    streak = next.dispatchClass().equals("A") ? Math.min(2, streak+1) : 0;
                }
                int active = Sql.one(c, "SELECT COUNT(*) FROM counters WHERE service_id=? AND owner_id IS NOT NULL AND paused=FALSE", r -> r.getInt(1), service).orElse(0);
                int occupied = Sql.one(c, "SELECT COUNT(*) FROM counters c JOIN queue_entries q ON q.occupied_counter=c.id WHERE c.service_id=? AND c.owner_id IS NOT NULL AND c.paused=FALSE", r -> r.getInt(1), service).orElse(0);
                Double mean = Sql.one(c, "SELECT CASE WHEN COUNT(*)>=5 THEN AVG(TIMESTAMPDIFF(SECOND,started_at,finished_at))/60.0 END FROM queue_entries WHERE service_id=? AND state='COMPLETED' AND finished_at>=?",
                        r -> Optional.ofNullable((Number)r.getObject(1)).map(Number::doubleValue), service, now.minus(Duration.ofDays(30))).orElse(Optional.empty()).orElse(null);
                final Double sampleMean = mean;
                var visits = Sql.query(c, """
                        (SELECT * FROM queue_entries WHERE service_id=? AND (?<>'USER' OR user_id=?)
                          AND state IN ('WAITING','CALLED','SERVING','SKIPPED'))
                        UNION ALL
                        (SELECT * FROM queue_entries WHERE service_id=? AND (?<>'USER' OR user_id=?)
                          AND state NOT IN ('WAITING','CALLED','SERVING','SKIPPED') ORDER BY id DESC LIMIT 100)
                        ORDER BY id DESC
                        """, r -> {
                    long id=r.getLong("id"); Integer position=positions.get(id);
                    Integer eta=position == null || active==0 || sampleMean==null ? null : (int)Math.ceil(((position - 1 + occupied) / active) * sampleMean);
                    return new Api.Visit(id, r.getLong("service_id"), r.getLong("user_id"), r.getDate("business_date") + "/" + service + "-" + r.getInt("token_number"), r.getString("state"), r.getString("source"), r.getString("dispatch_class"), r.getLong("version"), nullableLong(r,"counter_id"), r.getTimestamp("created_at").toInstant().toString(), r.getTimestamp("eligible_at").toInstant().toString(), position, eta);
                }, service, actor.role(), actor.id(), service, actor.role(), actor.id());
                // Admins also see closed/full windows so they can inspect retained inventory.
                var slots = Sql.query(c, """
                        SELECT p.* FROM appointment_slots p JOIN services s ON s.id=p.service_id
                        WHERE p.service_id=? AND p.ends_at>? AND (?='ADMIN' OR (
                          p.starts_at>? AND p.reserved<p.capacity AND s.open=TRUE
                          AND EXISTS(SELECT 1 FROM service_hours h WHERE h.service_id=p.service_id
                            AND h.day_of_week=WEEKDAY(CONVERT_TZ(p.starts_at,'+00:00','+05:30'))+1
                            AND h.opens<=TIME(CONVERT_TZ(p.starts_at,'+00:00','+05:30'))
                            AND h.closes>=TIME(CONVERT_TZ(p.ends_at,'+00:00','+05:30')))
                          AND NOT EXISTS(SELECT 1 FROM service_closures d WHERE d.service_id=p.service_id
                            AND d.closed_date=DATE(CONVERT_TZ(p.starts_at,'+00:00','+05:30'))))) ORDER BY p.starts_at
                        """, r -> new Api.Slot(r.getLong("id"), r.getLong("service_id"), r.getTimestamp("starts_at").toInstant().toString(), r.getTimestamp("ends_at").toInstant().toString(), r.getInt("capacity"), r.getInt("reserved")), service, now, actor.role(), now);
                var appointments = Sql.query(c, """
                        (SELECT a.*,s.starts_at,s.ends_at FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id
                          WHERE a.service_id=? AND (?<>'USER' OR a.user_id=?) AND a.state IN ('BOOKED','CHECKED_IN'))
                        UNION ALL
                        (SELECT a.*,s.starts_at,s.ends_at FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id
                          WHERE a.service_id=? AND (?<>'USER' OR a.user_id=?) AND a.state NOT IN ('BOOKED','CHECKED_IN')
                          ORDER BY a.id DESC LIMIT 100) ORDER BY id DESC
                        """,
                        r -> appointment(r),service,actor.role(),actor.id(),service,actor.role(),actor.id());
                var inbox = Sql.query(c, """
                        (SELECT * FROM notifications WHERE user_id=? AND is_read=FALSE)
                        UNION ALL
                        (SELECT * FROM notifications WHERE user_id=? AND is_read=TRUE ORDER BY id DESC LIMIT 100)
                        ORDER BY id DESC
                        """, r -> NotificationMessages.present(r.getLong("id"),r.getString("message"),r.getTimestamp("created_at").toInstant().toString(),r.getBoolean("is_read")),actor.id(),actor.id());
                c.commit();
                return new Api.Snapshot(actor,services,counters,visits,slots,appointments,inbox,now.toString());
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
    public Api.Page<Api.Visit> history(Api.User actor, long service, long beforeId, int limit) throws SQLException {
        pageRange(beforeId, limit);
        try (var c = db.getConnection()) {
            serviceAccess(c, actor, service);
            var rows = Sql.query(c, "SELECT * FROM queue_entries WHERE service_id=? AND (?<>'USER' OR user_id=?) AND (?=0 OR id<?) ORDER BY id DESC LIMIT ?",
                    r -> new Api.Visit(r.getLong("id"), service, r.getLong("user_id"), r.getDate("business_date") + "/" + service + "-" + r.getInt("token_number"),
                            r.getString("state"), r.getString("source"), r.getString("dispatch_class"), r.getLong("version"), nullableLong(r,"counter_id"),
                            r.getTimestamp("created_at").toInstant().toString(), r.getTimestamp("eligible_at").toInstant().toString(), null, null),
                    service, actor.role(), actor.id(), beforeId, beforeId, limit + 1);
            long next = rows.size() > limit ? rows.get(limit - 1).id() : 0;
            return new Api.Page<>(rows.subList(0, Math.min(limit, rows.size())), next);
        }
    }
    public Api.Page<Api.Appointment> appointmentHistory(Api.User actor, long service, long beforeId, int limit) throws SQLException {
        pageRange(beforeId, limit);
        try (var c = db.getConnection()) {
            serviceAccess(c, actor, service);
            var rows = Sql.query(c, "SELECT a.*,s.starts_at,s.ends_at FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id WHERE a.service_id=? AND (?<>'USER' OR a.user_id=?) AND (?=0 OR a.id<?) ORDER BY a.id DESC LIMIT ?",
                    QueryService::appointment, service, actor.role(), actor.id(), beforeId, beforeId, limit + 1);
            long next = rows.size() > limit ? rows.get(limit - 1).id() : 0;
            return new Api.Page<>(rows.subList(0, Math.min(limit, rows.size())), next);
        }
    }
    private static Api.Appointment appointment(ResultSet r) throws SQLException {
        return new Api.Appointment(r.getLong("id"),r.getLong("service_id"),r.getLong("slot_id"),r.getTimestamp("starts_at").toInstant().toString(),r.getTimestamp("ends_at").toInstant().toString(),r.getString("state"),r.getLong("version"),nullableLong(r,"visit_id"));
    }
    private static void pageRange(long beforeId, int limit) {
        if (beforeId < 0 || limit < 1 || limit > 100) throw new AppException(400,"INVALID_PAGE","Choose a page size from 1 to 100 and a non-negative cursor.");
    }
    private static void serviceAccess(Connection c, Api.User actor, long service) throws SQLException {
        boolean accessible = Sql.one(c,"SELECT id FROM services WHERE id=? AND (?<>'STAFF' OR EXISTS(SELECT 1 FROM staff_assignments WHERE service_id=? AND user_id=?))",
                r -> r.getLong(1),service,actor.role(),service,actor.id()).isPresent();
        if (!accessible) throw new AppException(403,"FORBIDDEN","Service unavailable to this account.");
    }
    /** The operator's initial report uses the same clock and rules as an explicitly chosen range. */
    public List<Api.Report> reports(Api.User actor) throws SQLException {
        LocalDate today = clock.instant().atZone(QueueService.ZONE).toLocalDate();
        return reports(actor, today.minusDays(29), today);
    }
    public List<Api.Report> reports(Api.User actor, LocalDate from, LocalDate to) throws SQLException {
        if (actor.role().equals("USER")) throw new AppException(403,"FORBIDDEN","Staff access is required for reports.");
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusYears(1))) throw new AppException(400,"INVALID_RANGE","Choose a date range of at most one year.");
        Instant start=from.atStartOfDay(QueueService.ZONE).toInstant(), end=to.plusDays(1).atStartOfDay(QueueService.ZONE).toInstant();
        try(var c=db.getConnection()) {
            return Sql.query(c,"""
                SELECT s.id,s.name,
                  (SELECT COUNT(*) FROM queue_entries q WHERE q.service_id=s.id AND q.state='COMPLETED' AND q.finished_at>=? AND q.finished_at<?) completed,
                  (SELECT COUNT(*) FROM queue_entries q WHERE q.service_id=s.id AND q.state='CANCELLED' AND q.finished_at>=? AND q.finished_at<?) cancelled,
                  (SELECT COUNT(*) FROM queue_entries q WHERE q.service_id=s.id AND q.state='NO_SHOW' AND q.finished_at>=? AND q.finished_at<?) no_shows,
                  (SELECT AVG(TIMESTAMPDIFF(SECOND,q.created_at,q.started_at))/60.0 FROM queue_entries q WHERE q.service_id=s.id AND q.state='COMPLETED' AND q.finished_at>=? AND q.finished_at<?) avg_wait,
                  (SELECT AVG(TIMESTAMPDIFF(SECOND,q.started_at,q.finished_at))/60.0 FROM queue_entries q WHERE q.service_id=s.id AND q.state='COMPLETED' AND q.finished_at>=? AND q.finished_at<?) avg_service,
                  (SELECT COUNT(*) FROM appointments a JOIN appointment_slots p ON p.id=a.slot_id WHERE a.service_id=s.id AND p.starts_at>=? AND p.starts_at<?) appointments,
                  (SELECT COUNT(*) FROM appointments a JOIN appointment_slots p ON p.id=a.slot_id WHERE a.service_id=s.id AND a.state='FULFILLED' AND p.starts_at>=? AND p.starts_at<?) fulfilled
                FROM services s WHERE ?='ADMIN' OR EXISTS(SELECT 1 FROM staff_assignments x WHERE x.service_id=s.id AND x.user_id=?) ORDER BY s.id
                """,r->new Api.Report(r.getLong("id"),r.getString("name"),r.getLong("completed"),r.getLong("cancelled"),r.getLong("no_shows"),number(r,"avg_wait"),number(r,"avg_service"),r.getLong("appointments"),r.getLong("fulfilled")),start,end,start,end,start,end,start,end,start,end,start,end,start,end,actor.role(),actor.id());
        }
    }
    public Api.ActivityReport activity(Api.User actor, long service, LocalDate from, LocalDate to) throws SQLException {
        if (actor.role().equals("USER")) throw new AppException(403,"FORBIDDEN","Staff access is required for reports.");
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusYears(1))) throw new AppException(400,"INVALID_RANGE","Choose a date range of at most one year.");
        long[][] counts = new long[7][24];
        try (var c = db.getConnection()) {
            serviceAccess(c, actor, service);
            // Asia/Kolkata is the configured product timezone; numeric offsets do not need MySQL timezone tables.
            Sql.query(c, """
                    SELECT WEEKDAY(CONVERT_TZ(started_at,'+00:00','+05:30')) weekday,
                           HOUR(CONVERT_TZ(started_at,'+00:00','+05:30')) hour, COUNT(*) total
                    FROM queue_entries WHERE service_id=? AND state='COMPLETED' AND started_at>=? AND started_at<?
                    GROUP BY weekday,hour
                    """, r -> { counts[r.getInt("weekday")][r.getInt("hour")] = r.getLong("total"); return r.getLong("total"); },
                    service, from.atStartOfDay(QueueService.ZONE).toInstant(), to.plusDays(1).atStartOfDay(QueueService.ZONE).toInstant());
        }
        return new Api.ActivityReport(from.toString(), to.toString(), counts);
    }
    public List<Api.Member> members(Api.User actor) throws SQLException {
        admin(actor);
        try(var c=db.getConnection()) {
            return Sql.query(c,"SELECT id,name,email,role,active FROM users ORDER BY id DESC LIMIT 500",r->new Api.Member(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5)));
        }
    }
    public List<Api.Hours> hours(long service) throws SQLException {
        try(var c=db.getConnection()) {
            return Sql.query(c,"SELECT day_of_week,opens,closes FROM service_hours WHERE service_id=? ORDER BY day_of_week",r->new Api.Hours(r.getInt(1),r.getTime(2).toString(),r.getTime(3).toString()),service);
        }
    }
    public List<Api.Closure> closures(long service) throws SQLException {
        try(var c=db.getConnection()) {
            return Sql.query(c,"SELECT closed_date,reason FROM service_closures WHERE service_id=? AND closed_date>=? ORDER BY closed_date LIMIT 100",r->new Api.Closure(r.getDate(1).toString(),r.getString(2)),service,clock.instant().atZone(QueueService.ZONE).toLocalDate());
        }
    }
    public List<Api.AuditEvent> audit(Api.User actor,long service) throws SQLException {
        admin(actor);
        try(var c=db.getConnection()) {
            return Sql.query(c,"SELECT id,actor_id,action,entity_id,detail,created_at FROM audit_events WHERE service_id=? ORDER BY id DESC LIMIT 100",r->new Api.AuditEvent(r.getLong(1),nullableLong(r,"actor_id"),r.getString(3),r.getLong(4),r.getString(5),r.getTimestamp(6).toInstant().toString()),service);
        }
    }
    private static void admin(Api.User actor) { if(!actor.role().equals("ADMIN"))throw new AppException(403,"FORBIDDEN","Administrator access is required."); }
    private static Double number(ResultSet r,String name) throws SQLException { var n=(Number)r.getObject(name); return n==null?null:n.doubleValue(); }
    private static Long nullableLong(ResultSet r,String name) throws SQLException { var n=(Number)r.getObject(name); return n==null?null:n.longValue(); }
    private static Instant instant(ResultSet r,int i) throws SQLException { var t=r.getTimestamp(i); return t==null?null:t.toInstant(); }
}
