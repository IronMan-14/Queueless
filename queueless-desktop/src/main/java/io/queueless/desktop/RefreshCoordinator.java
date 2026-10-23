package io.queueless.desktop;

/** Coalesces concurrent refresh triggers; generation fences obsolete callbacks. */
public final class RefreshCoordinator {
    private long generation;
    private long activeGeneration = -1;
    private boolean closed;

    public synchronized long begin() {
        if (closed || activeGeneration == generation) return -1;
        activeGeneration = generation;
        return generation;
    }

    public synchronized boolean finish(long ticket) {
        if (activeGeneration == ticket) activeGeneration = -1;
        return !closed && ticket == generation;
    }

    public synchronized long invalidate() {
        activeGeneration = -1;
        return ++generation;
    }

    public synchronized long generation() { return generation; }
    public synchronized boolean isCurrent(long ticket) { return !closed && generation == ticket; }
    public synchronized void close() { closed = true; invalidate(); }
}
