package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import io.queueless.server.service.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.queueless.server.integration.TestSupport.*;

@EnabledIfEnvironmentVariable(named="QUEUELESS_MULTI_PROCESS_TEST",matches="true")
class MultiProcessTest {
    static HikariDataSource db;static Backend a,b;
    static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void start() throws Exception {
        db=database();
        try {a=backend();b=backend();} catch(Exception e){stop();throw e;}
    }
    @AfterAll static void stop() throws Exception {if(a!=null)a.close();if(b!=null)b.close();if(db!=null)db.close();}
    @Test void independentBackendJvmsShareReceiptsAndFinalSlotCapacity() throws Exception {
        var f=fixture(db);Api.User second;String password="Process-test-"+UUID.randomUUID();String hash=AuthService.hash(password);
        try(var c=db.getConnection()){
            second=user(c,"USER");Sql.update(c,"UPDATE users SET password_hash=? WHERE id IN (?,?,?)",hash,f.user().id(),f.staff().id(),second.id());
        }
        var userA=login(a,f.user(),password);var userB=login(b,f.user(),password);
        var same=command("JOIN",f.service(),0,0,0,0,Map.of());
        List<HttpResponse<String>> responses=race(()->userA.command(same),()->userB.command(same));
        for(var r:responses)assertEquals(200,r.statusCode(),r.body());
        assertEquals(JSON.readValue(responses.get(0).body(),Api.Result.class).entityId(),JSON.readValue(responses.get(1).body(),Api.Result.class).entityId());
        assertEquals(1,count(db,"SELECT COUNT(*) FROM queue_entries WHERE service_id=?",f.service()));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM command_receipts WHERE actor_id=? AND request_id=?",f.user().id(),same.requestId()));
        long slot;
        var start=LocalDate.now(io.queueless.server.service.QueueService.ZONE).plusDays(1).atTime(10,0).atZone(io.queueless.server.service.QueueService.ZONE).toInstant();
        try(var c=db.getConnection()){slot=Sql.insert(c,"INSERT INTO appointment_slots(service_id,starts_at,ends_at,capacity) VALUES(?,?,?,1)",f.service(),start,start.plusSeconds(900));}
        var secondB=login(b,second,password);
        var bookings=race(()->userA.command(command("BOOK",f.service(),slot,0,0,0,Map.of())),()->secondB.command(command("BOOK",f.service(),slot,0,0,0,Map.of())));
        assertEquals(List.of(200,409),bookings.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1,count(db,"SELECT reserved FROM appointment_slots WHERE id=?",slot));
        assertEquals(1,count(db,"SELECT COUNT(*) FROM appointments WHERE slot_id=?",slot));
        assertEquals(0,count(db,"SELECT COUNT(*) FROM command_receipts WHERE status='PENDING'"));
    }
    @Test void cancellationAndDispatchCannotBothWinAcrossBackendProcesses() throws Exception {
        var f=fixture(db);String password="Race-test-"+UUID.randomUUID();String hash=AuthService.hash(password);
        try(var c=db.getConnection()){Sql.update(c,"UPDATE users SET password_hash=? WHERE id IN (?,?)",hash,f.user().id(),f.staff().id());}
        var visitor=login(a,f.user(),password);var operator=login(b,f.staff(),password);
        var visit=result(visitor.command(command("JOIN",f.service(),0,0,0,0,Map.of())));
        var claim=result(operator.command(command("CLAIM",f.service(),0,f.counter(),0,0,Map.of())));
        var results=race(()->visitor.command(command("CANCEL_VISIT",f.service(),visit.entityId(),0,visit.version(),0,Map.of())),()->operator.command(command("NEXT",f.service(),0,f.counter(),0,claim.version(),Map.of())));
        assertEquals(List.of(200,409),results.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1,count(db,"SELECT COUNT(*) FROM queue_entries WHERE id=? AND ((state='CANCELLED' AND counter_id IS NULL) OR (state='CALLED' AND occupied_counter=?))",visit.entityId(),f.counter()));
        assertEquals(2,count(db,"SELECT COUNT(*) FROM queue_events WHERE visit_id=?",visit.entityId()));
    }
    private static List<HttpResponse<String>> race(Callable<HttpResponse<String>> first,Callable<HttpResponse<String>> second)throws Exception{
        try(var executor=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var x=executor.submit(()->{gate.await();return first.call();});var y=executor.submit(()->{gate.await();return second.call();});gate.countDown();return List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));
        }
    }
    private static Backend backend() throws Exception {
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,ServerProcess.class.getName()).redirectErrorStream(true).start();
        var ready=new CompletableFuture<Integer>();var logs=new StringBuilder();
        Thread reader=new Thread(()->{try(var in=process.inputReader()){String line;while((line=in.readLine())!=null){synchronized(logs){if(logs.length()<12000)logs.append(line).append('\n');}if(line.startsWith("QUEUELESS_READY:"))ready.complete(Integer.parseInt(line.substring(16)));}}catch(Exception e){ready.completeExceptionally(e);}finally{if(!ready.isDone())ready.completeExceptionally(new IOException("Backend exited before readiness"));}},"test-backend-output");
        reader.setDaemon(true);reader.start();
        try{return new Backend(process,"http://127.0.0.1:"+ready.get(45,TimeUnit.SECONDS)+"/queueless");}
        catch(Exception e){process.destroyForcibly();throw new IllegalStateException("Backend startup failed: "+logs,e);}
    }
    private static Client login(Backend backend,Api.User user,String password)throws Exception{
        String email;try(var c=db.getConnection()){email=Sql.one(c,"SELECT email FROM users WHERE id=?",r->r.getString(1),user.id()).orElseThrow();}
        var http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        var anonymous=http.send(HttpRequest.newBuilder(URI.create(backend.base()+"/api/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
        var session=JSON.readValue(anonymous.body(),Api.Session.class);
        var client=new Client(http,backend.base(),session.csrfToken());
        var authenticated=client.post("/api/session",new Api.Login(email,password));assertEquals(200,authenticated.statusCode(),authenticated.body());
        return new Client(http,backend.base(),JSON.readValue(authenticated.body(),Api.Session.class).csrfToken());
    }
    private static Api.Result result(HttpResponse<String> r)throws Exception{assertEquals(200,r.statusCode(),r.body());return JSON.readValue(r.body(),Api.Result.class);}
    private record Client(HttpClient http,String base,String csrf){
        HttpResponse<String> command(Api.Command c)throws Exception{return post("/api/commands",c);}
        HttpResponse<String> post(String path,Object body)throws Exception{return http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
    private record Backend(Process process,String base) implements AutoCloseable{
        @Override public void close() throws InterruptedException {process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
}
