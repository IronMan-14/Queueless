package io.queueless.server.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Selects from one locked service snapshot; database reservation remains the authority. */
public interface QueuePolicy {
    record Candidate(long id, String dispatchClass, long readySequence, Instant appointmentStart) { }
    Optional<Candidate> select(List<Candidate> candidates, int appointmentStreak);
    static QueuePolicy forName(String name) { return "FIFO".equals(name) ? new Fifo() : new AppointmentAware(); }
    final class Fifo implements QueuePolicy {
        @Override public Optional<Candidate> select(List<Candidate> candidates, int streak) {
            return candidates.stream().min(Comparator.comparingLong(Candidate::readySequence));
        }
    }
    final class AppointmentAware implements QueuePolicy {
        @Override public Optional<Candidate> select(List<Candidate> candidates, int streak) {
            var walkIn = candidates.stream().filter(c -> c.dispatchClass().equals("W"))
                    .min(Comparator.comparingLong(Candidate::readySequence));
            var appointment = candidates.stream().filter(c -> c.dispatchClass().equals("A"))
                    .min(Comparator.comparing(Candidate::appointmentStart).thenComparingLong(Candidate::readySequence));
            return walkIn.isPresent() && streak == 2 ? walkIn : appointment.or(() -> walkIn);
        }
    }
}
