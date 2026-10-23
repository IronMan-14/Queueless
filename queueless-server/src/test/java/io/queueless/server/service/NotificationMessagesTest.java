package io.queueless.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queueless.contracts.Api;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.queueless.contracts.Api.NotificationKind.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationMessagesTest {
    @Test void everyPersistedVisitEventHasReadableTextAndSemanticKind() {
        var events = Map.of("JOIN", QUEUED, "CALL", CALLED, "START", SERVING, "COMPLETE", COMPLETED,
                "CANCEL_VISIT", CANCELLED, "REQUEUE", QUEUED, "RECALL", CALLED, "SKIP", MISSED,
                "ABORT", CANCELLED, "SYSTEM_NO_SHOW", MISSED);
        for (var event : events.entrySet()) {
            String raw = "Visit #12: " + event.getKey().toLowerCase(java.util.Locale.ROOT);
            var shown = NotificationMessages.present(44, raw, "2026-10-17T12:00:00Z", false);
            assertEquals(event.getValue(), shown.kind(), event.getKey());
            assertNotEquals(raw, shown.message());
            assertTrue(shown.message().contains("#12"));
            assertFalse(shown.message().contains("_"));
            assertEquals(44, shown.id());
            assertFalse(shown.read());
        }
        assertEquals("Visit #12 was cancelled.", NotificationMessages.present(1, "Visit #12: cancel_visit", "now", true).message());
    }
    @Test void everyPersistedAppointmentEventHasReadableTextAndSemanticKind() {
        var events = Map.of("BOOKED", BOOKED, "CANCELLED", CANCELLED, "RESCHEDULED", CHANGED,
                "CHECKED_IN", CHECKED_IN, "FULFILLED", COMPLETED, "ABORTED", CANCELLED, "SYSTEM_NO_SHOW", MISSED);
        for (var event : events.entrySet()) {
            String raw = "Appointment #7: " + event.getKey().toLowerCase(java.util.Locale.ROOT);
            var shown = NotificationMessages.present(50, raw, "2026-10-17T12:00:00Z", true);
            assertEquals(event.getValue(), shown.kind(), event.getKey());
            assertNotEquals(raw, shown.message());
            assertTrue(shown.message().contains("#7"));
            assertFalse(shown.message().contains("_"));
            assertTrue(shown.read());
        }
    }
    @Test void unknownTextIsPreservedAndLegacyConstructorDefaultsToInfo() {
        for (String raw : new String[]{"Visit #12: future_event", "Appointment #7: unknown", "<script>alert('text')</script>", "Visit #x: join", "Welcome!"}) {
            var shown = NotificationMessages.present(1, raw, "now", false);
            assertEquals(raw, shown.message());
            assertEquals(INFO, shown.kind());
        }
        assertEquals(INFO, new Api.Notification(1, "Legacy", "now", false).kind());
        assertEquals(INFO, new Api.Notification(1, "Legacy JSON", "now", false, null).kind());
    }

    @Test void skippedNoticeDoesNotPromiseAnotherReturnWhenEligibilityMayBeExhausted() {
        var shown = NotificationMessages.present(1, "Visit #12: skip", "now", false);
        assertEquals("Visit #12 was skipped. Open Queue & visits to check whether you can return.", shown.message());
        assertEquals(MISSED, shown.kind());
    }

    @Test void sharedJsonContractAcceptsOlderPayloadsAndKeepsSemanticKinds() throws Exception {
        var json = new ObjectMapper();
        var legacy = json.readValue("""
                {"id":1,"message":"Existing update","createdAt":"now","read":false}
                """, Api.Notification.class);
        assertEquals(INFO, legacy.kind());
        var current = NotificationMessages.present(2, "Visit #12: cancel_visit", "now", true);
        assertEquals(current, json.readValue(json.writeValueAsBytes(current), Api.Notification.class));
    }
}
