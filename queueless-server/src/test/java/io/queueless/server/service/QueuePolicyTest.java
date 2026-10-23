package io.queueless.server.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QueuePolicyTest {
    @Test void boundsAppointmentPreferenceAcrossDispatchesAndLateWalkIns() {
        var policy=new QueuePolicy.AppointmentAware();
        var entries=new ArrayList<>(List.of(candidate(1,"A",3),candidate(2,"A",4),candidate(3,"W",1),candidate(4,"A",5),candidate(5,"W",2)));
        int streak=0;
        var order=new ArrayList<Long>();
        while(!entries.isEmpty()) {
            var selected=policy.select(entries,streak).orElseThrow(); order.add(selected.id()); entries.remove(selected);
            streak=selected.dispatchClass().equals("A")?Math.min(2,streak+1):0;
        }
        assertEquals(List.of(1L,2L,3L,4L,5L),order);
        assertEquals(3,policy.select(List.of(candidate(3,"W",99),candidate(4,"A",1)),2).orElseThrow().id());
    }
    @Test void fifoUsesReadinessSequenceAndEmptyQueueIsEmpty() {
        assertEquals(1,new QueuePolicy.Fifo().select(List.of(candidate(2,"A",2),candidate(1,"W",1)),0).orElseThrow().id());
        assertTrue(new QueuePolicy.AppointmentAware().select(List.of(),2).isEmpty());
    }
    private QueuePolicy.Candidate candidate(long id,String kind,long seq) { return new QueuePolicy.Candidate(id,kind,seq,kind.equals("A")?Instant.EPOCH.plusSeconds(id):null); }
}
