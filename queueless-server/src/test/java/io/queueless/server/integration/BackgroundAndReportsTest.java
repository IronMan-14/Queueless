package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.server.db.Sql;
import io.queueless.server.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static io.queueless.server.integration.TestSupport.*;

@EnabledIfEnvironmentVariable(named="QUEUELESS_TEST_DB_URL",matches=".+")
class BackgroundAndReportsTest {
    static HikariDataSource db; Fixture f;
    @BeforeAll static void start(){db=database();}
    @AfterAll static void stop(){if(db!=null)db.close();}
    @BeforeEach void setup() throws Exception {f=TestSupport.fixture(db);}
    @Test void competingWorkersDeliverOnceAndLostMemorySignalsRecoverFromDatabase() throws Exception {
        long work;
        try(var c=db.getConnection()) {work=Sql.insert(c,"INSERT INTO notification_work(user_id,event_key,message) VALUES(?,?,'Durable event')",f.user().id(),UUID.randomUUID().toString());}
        try(var a=new BackgroundWork(db,Clock.systemUTC());var b=new BackgroundWork(db,Clock.systemUTC());var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var one=executor.submit(()->{gate.await();while(a.deliverOne()){}return true;});
            var two=executor.submit(()->{gate.await();while(b.deliverOne()){}return true;});
            gate.countDown();one.get(20,TimeUnit.SECONDS);two.get(20,TimeUnit.SECONDS);
        }
        assertEquals(1,count(db,"SELECT COUNT(*) FROM notifications WHERE work_id=?",work));
        assertEquals(1,count(db,"SELECT done FROM notification_work WHERE id=?",work));
        assertEquals(0,count(db,"SELECT COUNT(*) FROM notifications n JOIN notification_work w ON w.id=n.work_id WHERE w.done=FALSE"));
    }
    @Test void failureBeforeWorkerCommitRollsBackAndNextWorkerRecovers() throws Exception {
        // Drain unrelated fixtures so the injected failure targets this event.
        try(var worker=new BackgroundWork(db,Clock.systemUTC())){while(worker.deliverOne()){}}
        long work;
        try(var c=db.getConnection()){work=Sql.insert(c,"INSERT INTO notification_work(user_id,event_key,message) VALUES(?,?,'Rollback event')",f.user().id(),UUID.randomUUID().toString());}
        var fail=new AtomicBoolean(true);
        DataSource failing=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            Object returned=invoke(method,db,args);
            if(returned instanceof Connection connection) return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("commit") && fail.getAndSet(false))throw new SQLTransientConnectionException("Injected failure before commit","08006");
                return invoke(m,connection,a);
            });
            return returned;
        });
        try(var worker=new BackgroundWork(failing,Clock.systemUTC())){assertThrows(SQLException.class,worker::deliverOne);}
        assertEquals(0,count(db,"SELECT COUNT(*) FROM notifications WHERE work_id=?",work));
        assertEquals(0,count(db,"SELECT done FROM notification_work WHERE id=?",work));
        try(var recovered=new BackgroundWork(db,Clock.systemUTC())){while(recovered.deliverOne()){}}
        assertEquals(1,count(db,"SELECT COUNT(*) FROM notifications WHERE work_id=?",work));
    }
    @Test void expiryRetainsReservationAndDisruptionProtectionSurvivesReopening() throws Exception {
        Instant now=LocalDate.now(QueueService.ZONE).plusDays(2).atTime(12,0).atZone(QueueService.ZONE).toInstant();
        long ordinary,protectedAppointment,slot;
        try(var c=db.getConnection()) {
            slot=Sql.insert(c,"INSERT INTO appointment_slots(service_id,starts_at,ends_at,capacity,reserved) VALUES(?,?,?,2,2)",f.service(),now.minusSeconds(1800),now.minusSeconds(900));
            ordinary=Sql.insert(c,"INSERT INTO appointments(user_id,service_id,slot_id) VALUES(?,?,?)",f.user().id(),f.service(),slot);
            var another=user(c,"USER");
            protectedAppointment=Sql.insert(c,"INSERT INTO appointments(user_id,service_id,slot_id,disruption_protected) VALUES(?,?,?,TRUE)",another.id(),f.service(),slot);
        }
        try(var worker=new BackgroundWork(db,Clock.fixed(now,ZoneOffset.UTC))){worker.runDueTasks();worker.runDueTasks();}
        assertEquals(1,count(db,"SELECT COUNT(*) FROM appointments WHERE id=? AND state='NO_SHOW'",ordinary));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM appointments WHERE id=? AND state='BOOKED'",protectedAppointment));
        assertEquals(2,count(db,"SELECT reserved FROM appointment_slots WHERE id=?",slot));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM appointment_events WHERE appointment_id=? AND action='SYSTEM_NO_SHOW' AND actor_id IS NULL",ordinary));
    }
    @Test void reportsReconcileWithStoredTimestampsAndEmptySamplesRemainNull() throws Exception {
        Instant start=LocalDate.now(QueueService.ZONE).atTime(10,0).atZone(QueueService.ZONE).toInstant();
        var queue=new QueueService(db,new ObjectMapper(),Clock.fixed(start,ZoneOffset.UTC));
        var visit=queue.execute(f.user(),"user",command("JOIN",f.service(),0,0,0,0,Map.of()));
        try(var c=db.getConnection()) {Sql.update(c,"UPDATE queue_entries SET state='COMPLETED',created_at=?,started_at=?,finished_at=? WHERE id=?",start,start.plusSeconds(600),start.plusSeconds(900),visit.entityId());}
        var query=new QueryService(db,Clock.fixed(start.plusSeconds(1000),ZoneOffset.UTC));
        var date=start.atZone(QueueService.ZONE).toLocalDate();
        var report=query.reports(f.staff(),date,date).stream().filter(r->r.serviceId()==f.service()).findFirst().orElseThrow();
        assertEquals(1,report.completed());assertEquals(10.0,report.averageWaitMinutes());assertEquals(5.0,report.averageServiceMinutes());
        var empty=query.reports(f.staff(),date.minusDays(1),date.minusDays(1)).getFirst();
        assertEquals(0,empty.completed());assertNull(empty.averageWaitMinutes());assertNull(empty.averageServiceMinutes());
        assertThrows(AppException.class,()->query.reports(f.user(),date,date));
    }
    private static Object invoke(Method method,Object target,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
}
