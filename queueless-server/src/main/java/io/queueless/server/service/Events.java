package io.queueless.server.service;

import io.queueless.server.db.Sql;
import java.sql.*;
import java.util.Locale;

/** Durable history and inbox work are part of the caller's business transaction. */
final class Events {
    private Events() { }
    static void visit(Connection c,long visit,Long actor,String action,String detail) throws SQLException {
        long event=Sql.insert(c,"INSERT INTO queue_events(visit_id,actor_id,action,detail) VALUES(?,?,?,?)",visit,actor,action,detail);
        Sql.update(c,"INSERT INTO notification_work(user_id,event_key,message) SELECT user_id,?,? FROM queue_entries WHERE id=?","visit:"+event,"Visit #"+visit+": "+action.toLowerCase(Locale.ROOT),visit);
    }
    static void appointment(Connection c,long appointment,Long actor,String action) throws SQLException {
        long event=Sql.insert(c,"INSERT INTO appointment_events(appointment_id,actor_id,action) VALUES(?,?,?)",appointment,actor,action);
        Sql.update(c,"INSERT INTO notification_work(user_id,event_key,message) SELECT user_id,?,? FROM appointments WHERE id=?","appointment:"+event,"Appointment #"+appointment+": "+action.toLowerCase(Locale.ROOT),appointment);
    }
}
