package io.queueless.server.service;

import io.queueless.contracts.Api;
import java.util.Locale;
import java.util.regex.Pattern;
import static io.queueless.contracts.Api.NotificationKind.*;

/** Converts durable event payloads, including existing inbox rows, to a shared display model. */
final class NotificationMessages {
    private static final Pattern EVENT = Pattern.compile("^(Visit|Appointment) #([1-9][0-9]*): ([A-Za-z_]+)$");
    private NotificationMessages() { }

    static Api.Notification present(long id, String storedMessage, String createdAt, boolean read) {
        var match = EVENT.matcher(storedMessage);
        if (!match.matches()) return new Api.Notification(id, storedMessage, createdAt, read);
        String reference = match.group(1) + " #" + match.group(2);
        String action = match.group(3).toUpperCase(Locale.ROOT);
        Message display = match.group(1).equals("Visit") ? switch (action) {
            case "JOIN" -> new Message(reference + " joined the queue.", QUEUED);
            case "CALL" -> new Message(reference + " has been called. Please go to your assigned counter.", CALLED);
            case "START" -> new Message("Service has started for " + lower(reference) + ".", SERVING);
            case "COMPLETE" -> new Message(reference + " is complete.", COMPLETED);
            case "CANCEL_VISIT" -> new Message(reference + " was cancelled.", CANCELLED);
            case "REQUEUE" -> new Message(reference + " returned to the walk-in queue.", QUEUED);
            case "RECALL" -> new Message(reference + " has been called again. Please go to your assigned counter.", CALLED);
            case "SKIP" -> new Message(reference + " was skipped. Open Queue & visits to check whether you can return.", MISSED);
            case "ABORT" -> new Message(reference + " ended without completion.", CANCELLED);
            case "SYSTEM_NO_SHOW" -> new Message("The return window for " + lower(reference) + " has ended. The visit is now closed.", MISSED);
            default -> null;
        } : switch (action) {
            case "BOOKED" -> new Message(reference + " is booked. Your arrival window is reserved.", BOOKED);
            case "CANCELLED" -> new Message(reference + " was cancelled.", CANCELLED);
            case "RESCHEDULED" -> new Message(reference + " was rescheduled. Check your updated arrival window.", CHANGED);
            case "CHECKED_IN" -> new Message("You checked in for " + lower(reference) + ". Follow your visit in Queue & visits.", CHECKED_IN);
            case "FULFILLED" -> new Message(reference + " is complete.", COMPLETED);
            case "ABORTED" -> new Message(reference + " ended without completion.", CANCELLED);
            case "SYSTEM_NO_SHOW" -> new Message(reference + " was marked as missed because its check-in or return window ended.", MISSED);
            default -> null;
        };
        // Unknown text is preserved; clients escape it as ordinary text rather than treating it as markup.
        return display == null ? new Api.Notification(id, storedMessage, createdAt, read)
                : new Api.Notification(id, display.text(), createdAt, read, display.kind());
    }
    private static String lower(String reference) { return reference.substring(0, 1).toLowerCase(Locale.ROOT) + reference.substring(1); }
    private record Message(String text, Api.NotificationKind kind) { }
}
