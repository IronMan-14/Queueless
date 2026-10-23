package io.queueless.server.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AttemptLimiterTest {
    @Test void concurrentAttemptsCannotExceedPerKeyBudget()throws Exception{
        var limiter=new AttemptLimiter(Clock.systemUTC(),5,60000);
        try(var executor=Executors.newFixedThreadPool(10)){
            var gate=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<20;i++)results.add(executor.submit(()->{gate.await();return limiter.allow("same-client");}));
            gate.countDown();int accepted=0;for(var result:results)if(result.get(5,TimeUnit.SECONDS))accepted++;
            assertEquals(5,accepted);assertTrue(limiter.allow("different-client"));
        }
    }
}
