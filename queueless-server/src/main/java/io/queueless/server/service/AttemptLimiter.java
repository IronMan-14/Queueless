package io.queueless.server.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded, process-local login protection for the initial single-backend deployment. */
public final class AttemptLimiter {
    private final Clock clock;
    private final int limit;
    private final long windowMillis;
    private final Map<String,Window> windows=new HashMap<>();
    public AttemptLimiter(Clock clock,int limit,long windowMillis){
        if(limit<=0 || windowMillis<=0)throw new IllegalArgumentException("Positive rate limits required");
        this.clock=clock;this.limit=limit;this.windowMillis=windowMillis;
    }
    public synchronized boolean allow(String key){
        long now=clock.millis();
        windows.entrySet().removeIf(e->e.getValue().expiresAt()<=now);
        Window window=windows.get(key);
        if(window==null){if(windows.size()>=10000)return false;window=new Window(0,now+windowMillis);}
        if(window.count()>=limit)return false;
        windows.put(key,new Window(window.count()+1,window.expiresAt()));return true;
    }
    private record Window(int count,long expiresAt){}
}
