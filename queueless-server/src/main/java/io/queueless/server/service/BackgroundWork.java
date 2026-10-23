package io.queueless.server.service;

import io.queueless.server.db.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.concurrent.*;

/** In-memory signals accelerate work; committed database rows survive process loss. */
public final class BackgroundWork implements AutoCloseable {
    private final Logger log=LoggerFactory.getLogger(BackgroundWork.class);
    private final DataSource db;
    private final Clock clock;
    private final BlockingQueue<Boolean> signals=new ArrayBlockingQueue<>(32);
    private final ExecutorService consumer=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"queueless-inbox");t.setDaemon(true);return t;});
    private volatile boolean stopped;
    public BackgroundWork(DataSource db,Clock clock){this.db=db;this.clock=clock;}
    public void start(){consumer.submit(this::loop);}
    public void signal(){signals.offer(Boolean.TRUE);}
    private void loop(){
        while(!stopped){
            try {
                signals.poll(5,TimeUnit.SECONDS);
                runDueTasks();
                for(int n=0;n<100 && deliverOne();n++) { /* Bounded batch permits timely shutdown. */ }
            } catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(SQLException e){log.warn("Background work will retry: SQLState {}, vendor code {}",e.getSQLState(),e.getErrorCode());}
            catch(RuntimeException e){log.error("Background work failed; durable items remain pending",e);}
        }
    }
    /** One short database-only transaction; SKIP LOCKED is used only for this work queue. */
    public boolean deliverOne() throws SQLException {
        try(var c=db.getConnection()){
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try {
                var id=Sql.one(c,"SELECT id FROM notification_work WHERE done=FALSE AND due_at<=? ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",r->r.getLong(1),clock.instant());
                if(id.isEmpty()){c.rollback();return false;}
                Sql.update(c,"INSERT INTO notifications(user_id,work_id,message) SELECT user_id,id,message FROM notification_work WHERE id=?",id.get());
                Sql.update(c,"UPDATE notification_work SET done=TRUE WHERE id=?",id.get());
                c.commit();return true;
            }catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    /** Candidate reads are hints. Each command locks service then user and rechecks current state. */
    public void runDueTasks() throws SQLException {
        try(var c=db.getConnection()){
            var bookings=Sql.query(c,"""
                SELECT a.id,a.service_id,a.user_id FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id JOIN services v ON v.id=a.service_id
                WHERE a.state='BOOKED' AND s.starts_at<=? AND (
                  (s.ends_at<? AND a.disruption_protected=FALSE AND v.open=TRUE AND NOT EXISTS(
                    SELECT 1 FROM service_closures x WHERE x.service_id=a.service_id AND x.closed_date=DATE(CONVERT_TZ(s.starts_at,'+00:00','+05:30'))))
                  OR (s.ends_at>=? AND NOT EXISTS(SELECT 1 FROM notification_work w WHERE w.event_key=CONCAT('reminder:',a.id,':',a.version))))
                ORDER BY a.id LIMIT 100
                """,r->new Due(r.getLong(1),r.getLong(2),r.getLong(3)),clock.instant().plusSeconds(3600),clock.instant().minusSeconds(600),clock.instant().minusSeconds(600));
            for(var due:bookings) appointment(due);
            var skipped=Sql.query(c,"SELECT id,service_id,user_id FROM queue_entries WHERE state='SKIPPED' AND return_deadline<? ORDER BY id LIMIT 100",r->new Due(r.getLong(1),r.getLong(2),r.getLong(3)),clock.instant());
            for(var due:skipped) skipped(due);
        }
    }
    private void appointment(Due due) throws SQLException {
        locked(due,c->{
            var row=Sql.one(c,"SELECT a.state,a.version,a.disruption_protected,s.starts_at,s.ends_at FROM appointments a JOIN appointment_slots s ON s.id=a.slot_id WHERE a.id=? FOR UPDATE",r->new Booking(r.getString(1),r.getLong(2),r.getBoolean(3),r.getTimestamp(4).toInstant(),r.getTimestamp(5).toInstant()),due.id()).orElseThrow();
            if(!row.state().equals("BOOKED"))return null;
            var now=clock.instant();
            if(now.isAfter(row.ends().plusSeconds(600))){
                boolean closed=Sql.one(c,"SELECT id FROM services WHERE id=? AND open=FALSE",r->r.getLong(1),due.service()).isPresent()
                        || Sql.one(c,"SELECT service_id FROM service_closures WHERE service_id=? AND closed_date=?",r->r.getLong(1),due.service(),row.starts().atZone(QueueService.ZONE).toLocalDate()).isPresent();
                if(!row.protectedArrival() && !closed){Sql.update(c,"UPDATE appointments SET state='NO_SHOW',version=version+1 WHERE id=?",due.id());Events.appointment(c,due.id(),null,"SYSTEM_NO_SHOW");}
            } else if(!now.isBefore(row.starts().minusSeconds(3600))) {
                Sql.update(c,"INSERT IGNORE INTO notification_work(user_id,event_key,message) VALUES(?,?,?)",due.user(),"reminder:"+due.id()+":"+row.version(),"Appointment #"+due.id()+" is approaching. Check in from 10 minutes before your arrival window.");
            }
            return null;
        });
    }
    private void skipped(Due due) throws SQLException {
        locked(due,c->{
            if(Sql.update(c,"UPDATE queue_entries SET state='NO_SHOW',counter_id=NULL,finished_at=?,version=version+1 WHERE id=? AND state='SKIPPED' AND return_deadline<?",clock.instant(),due.id(),clock.instant())==1){
                Events.visit(c,due.id(),null,"SYSTEM_NO_SHOW","Return window expired");
                var linked=Sql.one(c,"SELECT id FROM appointments WHERE visit_id=? AND state='CHECKED_IN'",r->r.getLong(1),due.id());
                if(linked.isPresent()){Sql.update(c,"UPDATE appointments SET state='NO_SHOW',version=version+1 WHERE id=?",linked.get());Events.appointment(c,linked.get(),null,"SYSTEM_NO_SHOW");}
            }
            return null;
        });
    }
    private void locked(Due due,Sql.Work<Void> work) throws SQLException {
        try(var c=db.getConnection()){
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            try {Sql.one(c,"SELECT service_id FROM service_runtime WHERE service_id=? FOR UPDATE",r->r.getLong(1),due.service());Sql.one(c,"SELECT id FROM users WHERE id=? FOR UPDATE",r->r.getLong(1),due.user());work.run(c);c.commit();}
            catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }
    }
    @Override public void close(){stopped=true;consumer.shutdownNow();try{if(!consumer.awaitTermination(10,TimeUnit.SECONDS))log.warn("Background shutdown did not finish within ten seconds");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private record Due(long id,long service,long user){}
    private record Booking(String state,long version,boolean protectedArrival,Instant starts,Instant ends){}
}
