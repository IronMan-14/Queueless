package io.queueless.contracts;

import java.util.List;
import java.util.Map;

/** Version 1 transport records; no persistence objects or client-side business authority. */
public final class Api {
    private Api() { }
    public record Login(String email, String password) { }
    public record Registration(String name, String email, String password) { }
    public record User(long id, String name, String role) { }
    public record Session(User user, String csrfToken) { }
    public record Service(long id, String name, String description, String policy, boolean open,
                          int walkInLimit, int plannedMinutes, int referenceMinutes) { }
    public record Counter(long id, long serviceId, String name, Long ownerId, long claimVersion,
                          boolean paused, Long visitId, String visitState) { }
    public record Visit(long id, long serviceId, long userId, String token, String state, String source,
                        String dispatchClass, long version, Long counterId, String createdAt,
                        String eligibleAt, Integer position, Integer estimatedMinutes) { }
    public record Slot(long id, long serviceId, String startsAt, String endsAt, int capacity, int reserved) { }
    public record Appointment(long id, long serviceId, long slotId, String startsAt, String endsAt,
                              String state, long version, Long visitId) { }
    /** Semantic event category; message is already suitable for both browser and desktop display. */
    public enum NotificationKind { INFO, QUEUED, CALLED, SERVING, COMPLETED, CANCELLED, MISSED, BOOKED, CHANGED, CHECKED_IN }
    public record Notification(long id, String message, String createdAt, boolean read, NotificationKind kind) {
        public Notification { if (kind == null) kind = NotificationKind.INFO; }
        public Notification(long id, String message, String createdAt, boolean read) {
            this(id, message, createdAt, read, NotificationKind.INFO);
        }
    }
    /** Stable descending-ID pagination: zero means there is no older page. */
    public record Page<T>(List<T> items, long nextBeforeId) {
        public Page { items = List.copyOf(items); }
    }
    public record Snapshot(User user, List<Service> services, List<Counter> counters, List<Visit> visits,
                           List<Slot> slots, List<Appointment> appointments, List<Notification> notifications,
                           String serverTime) { }
    /** Fields are operation-specific input only; the server validates every value and authority. */
    public record Command(String requestId, String operation, long serviceId, long entityId,
                          long counterId, long expectedVersion, long claimVersion, Map<String, String> fields) {
        public Command { fields = fields == null ? Map.of() : Map.copyOf(fields); }
    }
    public record Result(long entityId, long version, String message, boolean replayed) { }
    public record Problem(String code, String message, boolean retryable) { }
    public record Report(long serviceId, String serviceName, long completed, long cancelled, long noShows,
                         Double averageWaitMinutes, Double averageServiceMinutes, long appointments,
                         long fulfilledAppointments) { }
    public record Member(long id, String name, String email, String role, boolean active) { }
    public record Hours(int day, String opens, String closes) { }
    public record Closure(String date, String reason) { }
    public record AuditEvent(long id, Long actorId, String action, long entityId, String detail, String createdAt) { }
    /** Monday-first rows, local start hours 0..23; contains actual completed-visit counts. */
    public record ActivityReport(String from, String to, long[][] completedByStartHour) {
        public ActivityReport { completedByStartHour = copy(completedByStartHour); }
        @Override public long[][] completedByStartHour() { return copy(completedByStartHour); }
        private static long[][] copy(long[][] source) {
            long[][] result = new long[source.length][];
            for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
            return result;
        }
    }
}
