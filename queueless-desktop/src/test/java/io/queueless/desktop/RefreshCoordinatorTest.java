package io.queueless.desktop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RefreshCoordinatorTest {
    @Test void simultaneousTriggersAdmitOnlyOneRefresh() throws Exception {
        RefreshCoordinator coordinator = new RefreshCoordinator();
        CountDownLatch ready = new CountDownLatch(12), start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(12)) {
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < 12; i++) results.add(workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return coordinator.begin();
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS) >= 0) accepted++;
            assertEquals(1, accepted);
        }
    }

    @Test void oldResponsesCannotClearTheNewRefreshOrUpdateAnotherSession() {
        RefreshCoordinator coordinator = new RefreshCoordinator();
        long old = coordinator.begin();
        coordinator.invalidate();
        long current = coordinator.begin();
        assertFalse(coordinator.finish(old));
        assertEquals(-1, coordinator.begin(), "An obsolete completion must not clear the new in-flight refresh.");
        assertTrue(coordinator.finish(current));
        assertEquals(current, coordinator.begin());
    }

    @Test void shutdownRejectsNewWorkAndAllOutstandingCallbacks() {
        RefreshCoordinator coordinator = new RefreshCoordinator();
        long ticket = coordinator.begin();
        coordinator.close();
        assertFalse(coordinator.finish(ticket));
        assertFalse(coordinator.isCurrent(ticket));
        assertEquals(-1, coordinator.begin());
    }
}
