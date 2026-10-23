package io.queueless.server.service;

import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import java.sql.*;
import java.time.*;
import static io.queueless.server.service.AppException.require;

/** Admission rules run on the command's existing transaction and locked service root. */
final class Admissions {
    private final Clock clock;
    Admissions(Clock clock) { this.clock=clock; }
    Api.Result join(Connection c,long user,long service,Instant appointmentStart) throws SQLException {
        var now=clock.instant();
        if(appointmentStart==null) { requireOpen(c,service,now); capacity(c,service); }
        require(Sql.one(c,"SELECT id FROM queue_entries WHERE service_id=? AND unresolved_user=?",r->r.getLong(1),service,user).isEmpty(),"Resolve your current visit for this service first.");
        var date=now.atZone(QueueService.ZONE).toLocalDate();
        Sql.update(c,"INSERT INTO queue_sessions(service_id,business_date,last_token) VALUES(?,?,0) ON DUPLICATE KEY UPDATE service_id=service_id",service,date);
        Sql.update(c,"UPDATE queue_sessions SET last_token=last_token+1 WHERE service_id=? AND business_date=?",service,date);
        int token=Sql.one(c,"SELECT last_token FROM queue_sessions WHERE service_id=? AND business_date=?",r->r.getInt(1),service,date).orElseThrow();
        long seq=sequence(c,service);
        long id=Sql.insert(c,"INSERT INTO queue_entries(service_id,user_id,business_date,token_number,state,source,dispatch_class,ready_sequence,eligible_at,appointment_start,created_at) VALUES(?,?,?,?,'WAITING',?,?,?,?,?,?)",
                service,user,date,token,appointmentStart==null?"WALK_IN":"APPOINTMENT",appointmentStart==null?"W":"A",seq,
                appointmentStart!=null && appointmentStart.isAfter(now)?appointmentStart:now,appointmentStart,now);
        Events.visit(c,id,user,"JOIN","");
        return new Api.Result(id,0,"Token "+service+"-"+token+" issued.",false);
    }
    void requireOpen(Connection c,long service,Instant now) throws SQLException {
        var local=now.atZone(QueueService.ZONE);
        boolean open=Sql.one(c,"SELECT open FROM services WHERE id=?",r->r.getBoolean(1),service).orElse(false);
        boolean hours=Sql.one(c,"SELECT service_id FROM service_hours WHERE service_id=? AND day_of_week=? AND opens<=? AND closes>?",r->r.getLong(1),service,local.getDayOfWeek().getValue(),local.toLocalTime(),local.toLocalTime()).isPresent();
        boolean closure=Sql.one(c,"SELECT service_id FROM service_closures WHERE service_id=? AND closed_date=?",r->r.getLong(1),service,local.toLocalDate()).isPresent();
        require(open && hours && !closure,"This service is closed to new walk-ins.");
    }
    void capacity(Connection c,long service) throws SQLException {
        int limit=Sql.one(c,"SELECT walk_in_limit FROM services WHERE id=?",r->r.getInt(1),service).orElseThrow();
        long count=Sql.one(c,"SELECT COUNT(*) FROM queue_entries WHERE service_id=? AND dispatch_class='W' AND state IN ('WAITING','CALLED','SERVING')",r->r.getLong(1),service).orElseThrow();
        require(count<limit,"Walk-in capacity has been reached.");
    }
    long sequence(Connection c,long service) throws SQLException {
        Sql.update(c,"UPDATE service_runtime SET ready_sequence=ready_sequence+1,revision=revision+1 WHERE service_id=?",service);
        return Sql.one(c,"SELECT ready_sequence FROM service_runtime WHERE service_id=?",r->r.getLong(1),service).orElseThrow();
    }
}
