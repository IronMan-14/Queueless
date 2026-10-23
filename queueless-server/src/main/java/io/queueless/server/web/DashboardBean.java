package io.queueless.server.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queueless.contracts.Api;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/** JavaBean presentation adapter: JSP EL never relies on record accessor conventions. */
public final class DashboardBean {
    private final Map<String, Object> values;
    private final long serviceId;
    public DashboardBean(Api.Snapshot snapshot, ObjectMapper json, long serviceId) {
        values = json.convertValue(snapshot, new TypeReference<>() { }); this.serviceId = serviceId;
    }
    public Map<String, Object> getUser() { return map("user"); }
    public List<Map<String, Object>> getServices() { return list("services"); }
    public List<Map<String, Object>> getCounters() { return list("counters"); }
    public List<Map<String, Object>> getVisits() { return list("visits"); }
    public List<Map<String, Object>> getSlots() { return list("slots"); }
    public List<Map<String, Object>> getAppointments() { return list("appointments"); }
    public List<Map<String, Object>> getNotifications() {
        return list("notifications").stream().map(notification -> {
            var view = new LinkedHashMap<>(notification);
            String kind = (String) notification.get("kind");
            view.put("kindLabel", switch (kind) {
                case "QUEUED" -> "Queue joined";
                case "CALLED" -> "Visitor called";
                case "SERVING" -> "Service started";
                case "COMPLETED" -> "Completed";
                case "CANCELLED" -> "Cancelled or ended";
                case "MISSED" -> "Missed or skipped";
                case "BOOKED" -> "Appointment booked";
                case "CHANGED" -> "Appointment changed";
                case "CHECKED_IN" -> "Checked in";
                default -> "Update";
            });
            return (Map<String, Object>) view;
        }).toList();
    }
    public long getUnreadCount() { return list("notifications").stream().filter(n -> Boolean.FALSE.equals(n.get("read"))).count(); }
    public long getBulkNotificationCount() { return Math.min(100, getUnreadCount()); }
    public String getUnreadNotificationIds() {
        return list("notifications").stream().filter(n -> Boolean.FALSE.equals(n.get("read"))).limit(100)
                .map(n -> n.get("id").toString()).collect(Collectors.joining(","));
    }
    public String getServerTime() { return (String) values.get("serverTime"); }
    public long getServiceId() { return serviceId; }
    public boolean isOperator() { return !"USER".equals(getUser().get("role")); }
    public boolean isAdmin() { return "ADMIN".equals(getUser().get("role")); }
    public Map<String, Object> getSelectedService() { return getServices().stream().filter(s -> ((Number)s.get("id")).longValue() == serviceId).findFirst().orElse(Map.of()); }
    @SuppressWarnings("unchecked") private Map<String, Object> map(String key) { return (Map<String, Object>) values.get(key); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> list(String key) { return (List<Map<String, Object>>) values.getOrDefault(key, List.of()); }
    public static final class FormIds { public String getNext() { return UUID.randomUUID().toString(); } }
}
