package io.queueless.server.web;

import io.queueless.contracts.Api;
import io.queueless.server.service.AppException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class BrowserServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        try {
            String path = request.getServletPath();
            if (path.equals("/login") || path.equals("/register")) {
                if (WebSupport.current(request) != null) { redirect(request, response, "/app"); return; }
                takeNotice(request);
                request.setAttribute("register", path.equals("/register")); view(request, response, "auth"); return;
            }
            var user = WebSupport.requireUser(request);
            String workspaceView = path.equals("/app") ? workspaceView(request, user) : null;
            long serviceId = WebSupport.number(request.getParameter("serviceId"), 0);
            if (path.equals("/history") || path.equals("/appointment-history")) {
                var queries = WebSupport.app(request).queries();
                long before = WebSupport.number(request.getParameter("beforeId"), 0);
                var history = path.equals("/history") ? queries.history(user, serviceId, before, 50) : queries.appointmentHistory(user, serviceId, before, 50);
                request.setAttribute("history", WebSupport.app(request).json().convertValue(history, Map.class));
                request.setAttribute("appointmentHistory", path.equals("/appointment-history"));
                request.setAttribute("serviceId", serviceId);
                view(request, response, "history"); return;
            }
            var snapshot = WebSupport.app(request).queries().snapshot(user, serviceId);
            if (serviceId == 0 && !snapshot.services().isEmpty()) {
                serviceId = snapshot.services().getFirst().id(); snapshot = WebSupport.app(request).queries().snapshot(user, serviceId);
            }
            request.setAttribute("dashboard", new DashboardBean(snapshot, WebSupport.app(request).json(), serviceId));
            var queries = WebSupport.app(request).queries();
            if (path.equals("/reports")) {
                var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
                java.time.LocalDate to, from;
                try { to = ApiServlet.date(request.getParameter("to"), today); from = ApiServlet.date(request.getParameter("from"), to.minusDays(29)); }
                catch (java.time.format.DateTimeParseException invalid) { throw new AppException(400, "INVALID_RANGE", "Choose valid report dates."); }
                long selected = serviceId;
                request.setAttribute("reports", WebSupport.app(request).json().convertValue(queries.reports(user, from, to).stream().filter(r -> r.serviceId() == selected).toList(), List.class));
                request.setAttribute("activity", WebSupport.app(request).json().convertValue(queries.activity(user, serviceId, from, to), Map.class));
                request.setAttribute("from", from.toString()); request.setAttribute("to", to.toString());
                view(request, response, "reports"); return;
            }
            if (workspaceView == null) throw new AppException(404, "NOT_FOUND", "Page not found.");
            if (Set.of("visits", "appointments", "administration").contains(workspaceView)) {
                request.setAttribute("hours", WebSupport.app(request).json().convertValue(queries.hours(serviceId), List.class));
                request.setAttribute("closures", WebSupport.app(request).json().convertValue(queries.closures(serviceId), List.class));
            }
            if (workspaceView.equals("administration")) {
                request.setAttribute("members", WebSupport.app(request).json().convertValue(queries.members(user), List.class));
                request.setAttribute("audit", WebSupport.app(request).json().convertValue(queries.audit(user, serviceId), List.class));
            }
            takeNotice(request);
            view(request, response, "dashboard");
        } catch (AppException error) {
            if (acceptsJson(request)) { problem(request, response, error.status(), error.code(), error.getMessage(), error.status() == 503); return; }
            if (error.status() == 401) { redirect(request, response, "/login"); return; }
            failure(request, response, error.status(), error.getMessage());
        } catch (Exception error) { unexpected(request, response, error); }
    }
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        Map<String, String> form = Map.of();
        try {
            form = form(request); WebSupport.checkCsrf(request, form.get("csrf"));
            var app = WebSupport.app(request);
            switch (request.getServletPath()) {
                case "/login" -> {
                    WebSupport.signIn(request, app.auth().login(new Api.Login(form.get("email"), form.get("password"))));
                    request.getSession().setAttribute("notice", "You're signed in. Choose a service to manage your visit or appointment.");
                }
                case "/register" -> {
                    WebSupport.signIn(request, app.auth().register(new Api.Registration(form.get("name"), form.get("email"), form.get("password"))));
                    request.getSession().setAttribute("notice", "Your account is ready. Choose a service to join a queue or book an appointment.");
                }
                case "/logout" -> {
                    var session = request.getSession(false); if (session != null) session.invalidate();
                    request.getSession(true).setAttribute("notice", "You're signed out. You can sign in again whenever you're ready.");
                    redirect(request, response, "/login"); return;
                }
                case "/command" -> {
                    var user = WebSupport.requireUser(request);
                    var fields = new LinkedHashMap<>(form);
                    for (String key : List.of("csrf", "requestId", "operation", "serviceId", "entityId", "counterId", "expectedVersion", "claimVersion")) fields.remove(key);
                    var command = new Api.Command(form.get("requestId"), form.get("operation"), number(form, "serviceId"), number(form, "entityId"), number(form, "counterId"), number(form, "expectedVersion"), number(form, "claimVersion"), fields);
                    var result = app.queue().execute(user, request.getSession().getId(), command);
                    String message = successMessage(command, result) + (result.replayed() ? " (Previously completed request; no duplicate action was taken.)" : "");
                    request.getSession().setAttribute("notice", message);
                    long selectedService = command.operation().equals("CREATE_SERVICE") ? result.entityId() : command.serviceId();
                    String destination = "/app?serviceId=" + selectedService + "&view=" + commandView(command.operation());
                    if (acceptsJson(request)) {
                        response.setContentType("application/json");
                        app.json().writeValue(response.getOutputStream(), Map.of("redirect", request.getContextPath() + destination, "message", message, "replayed", result.replayed()));
                    } else redirect(request, response, destination);
                    return;
                }
                default -> throw new AppException(404, "NOT_FOUND", "Page not found.");
            }
            redirect(request, response, "/app?serviceId=" + number(form, "serviceId"));
        } catch (AppException error) {
            if (acceptsJson(request)) { problem(request, response, error.status(), error.code(), error.getMessage(), error.status() == 503); return; }
            if (request.getServletPath().equals("/login") || request.getServletPath().equals("/register")) {
                response.setStatus(error.status()); request.setAttribute("error", error.getMessage()); request.setAttribute("register", request.getServletPath().equals("/register")); view(request, response, "auth");
            } else {
                if (error.status() == 503 && request.getServletPath().equals("/command")) request.setAttribute("retryFields", form);
                failure(request, response, error.status(), error.getMessage());
            }
        } catch (Exception error) {
            if (request.getServletPath().equals("/command")) request.setAttribute("retryFields", form);
            unexpected(request, response, error);
        }
    }
    /** Presentation text only; persisted results and business rules stay in the shared service layer. */
    private static String successMessage(Api.Command command, Api.Result result) {
        return switch (command.operation()) {
            case "JOIN" -> result.message() + " Your place in the queue is saved.";
            case "BOOK" -> "Appointment booked. Your arrival window is reserved; service may start later.";
            case "CHECK_IN" -> result.message() + " Follow your visit below.";
            case "CANCEL_APPOINTMENT" -> "Appointment cancelled. Its reserved space is available again.";
            case "RESCHEDULE" -> "Appointment rescheduled. Your new arrival window is reserved and the previous reservation has been released.";
            case "CANCEL_VISIT" -> "Visit cancelled and removed from the active queue.";
            case "REQUEUE" -> "Visit returned to the end of the walk-in queue. Follow its updated position below.";
            case "CLAIM" -> "You're now operating this counter.";
            case "RELEASE" -> "Counter released. Another staff member can now claim it.";
            case "TAKEOVER" -> "Counter transferred to you. Any current visit remains assigned.";
            case "PAUSE" -> Boolean.parseBoolean(command.fields().get("paused")) ? "Counter paused. No new visitors will be called here until you resume it." : "Counter resumed. New calls are enabled.";
            case "NEXT" -> "Next visitor called. Start service when they arrive.";
            case "START" -> "Service started for this visit.";
            case "COMPLETE" -> "Visit completed. You can call the next waiting visitor.";
            case "SKIP" -> "Visit skipped. Check Queue & visits for return eligibility.";
            case "RECALL" -> "Visitor called again at this counter.";
            case "ABORT" -> "Visit ended without completion. The reason has been saved.";
            case "OPEN_DATE" -> "Date reopened. Admissions will follow the service's working hours and settings.";
            case "CREATE_COUNTER" -> "Counter created. An assigned staff member can now claim it.";
            case "ASSIGN_STAFF" -> "Staff member assigned to this service.";
            case "UNASSIGN_STAFF" -> "Staff assignment removed from this service.";
            case "UPDATE_SLOT" -> "Arrival window capacity updated. Existing reservations are retained.";
            default -> result.message();
        };
    }
    /** Validate direct links as well as navigation; hiding a link is not authorization. */
    private static String workspaceView(HttpServletRequest request, Api.User user) {
        String selected = Objects.requireNonNullElse(request.getParameter("view"), "overview");
        String[] page = switch (selected) {
            case "overview" -> new String[]{"Overview", "A snapshot of your visits, appointments and updates."};
            case "visits" -> new String[]{"Queue & visits", "Join a queue, follow your place and manage your visits."};
            case "appointments" -> new String[]{"Appointments", "Find an arrival window, manage your bookings and check in."};
            case "notifications" -> new String[]{"Notifications", "Keep up with updates about your visits and appointments."};
            case "counters" -> new String[]{"Service counters", "Manage your counter and serve the next visitor."};
            case "reports" -> new String[]{"Reports", "Explore service activity and export results from recorded visits."};
            case "administration" -> new String[]{"Administration", "Manage services, working hours, staff and appointment capacity."};
            default -> throw new AppException(404, "NOT_FOUND", "Page not found.");
        };
        if (selected.equals("administration") && !user.role().equals("ADMIN")) {
            throw new AppException(403, "FORBIDDEN", "Only administrators can manage service settings.");
        }
        if ((selected.equals("counters") || selected.equals("reports")) && !Set.of("STAFF", "ADMIN").contains(user.role())) {
            throw new AppException(403, "FORBIDDEN", "This page is available to service staff and administrators.");
        }
        request.setAttribute("workspaceView", selected);
        request.setAttribute("pageTitle", page[0]);
        request.setAttribute("pageDescription", page[1]);
        return selected;
    }
    private static String commandView(String operation) {
        return switch (operation) {
            case "JOIN", "CANCEL_VISIT", "REQUEUE", "CHECK_IN" -> "visits";
            case "BOOK", "CANCEL_APPOINTMENT", "RESCHEDULE" -> "appointments";
            case "READ_NOTIFICATION", "READ_NOTIFICATIONS" -> "notifications";
            case "CLAIM", "RELEASE", "TAKEOVER", "PAUSE", "NEXT", "START", "COMPLETE", "SKIP", "RECALL", "ABORT" -> "counters";
            default -> "administration";
        };
    }
    private static boolean acceptsJson(HttpServletRequest request) { return Objects.toString(request.getHeader("Accept"), "").toLowerCase(Locale.ROOT).contains("application/json"); }
    private static void takeNotice(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) { request.setAttribute("notice", session.getAttribute("notice")); session.removeAttribute("notice"); }
    }
    private void problem(HttpServletRequest request, HttpServletResponse response, int status, String code, String message, boolean retryable) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer(); response.setStatus(status); response.setContentType("application/json");
        WebSupport.app(request).json().writeValue(response.getOutputStream(), new Api.Problem(code, message, retryable));
    }
    private static long number(Map<String, String> form, String key) { return WebSupport.number(form.get(key), 0); }
    private static Map<String, String> form(HttpServletRequest request) throws IOException {
        if (request.getContentType() == null || !request.getContentType().startsWith("application/x-www-form-urlencoded")) throw new AppException(415, "CONTENT_TYPE", "Submit a form to this page.");
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8); var form = new LinkedHashMap<String, String>();
        try {
            for (String pair : body.split("&")) {
                if (pair.isEmpty()) continue; String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8); String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
                if (form.putIfAbsent(key, value) != null) throw new AppException(400, "INVALID_INPUT", "Duplicate form fields are not accepted.");
            }
        } catch (IllegalArgumentException error) { throw new AppException(400, "INVALID_INPUT", "The form encoding is invalid."); }
        return form;
    }
    private void view(HttpServletRequest request, HttpServletResponse response, String name) throws ServletException, IOException {
        response.setContentType("text/html; charset=UTF-8");
        request.setAttribute("csrf", WebSupport.csrf(request.getSession(true)));
        request.setAttribute("ids", new DashboardBean.FormIds());
        request.getRequestDispatcher("/WEB-INF/views/" + name + ".jsp").forward(request, response);
    }
    private void failure(HttpServletRequest request, HttpServletResponse response, int status, String message) throws ServletException, IOException {
        response.setStatus(status); request.setAttribute("error", message); view(request, response, "error");
    }
    private void unexpected(HttpServletRequest request, HttpServletResponse response, Exception error) throws ServletException, IOException {
        String reference = UUID.randomUUID().toString(); getServletContext().log("Page failure " + reference, error);
        if (acceptsJson(request)) { problem(request, response, 500, "SERVER_ERROR", "We could not confirm the outcome. Retry the same request. Reference: " + reference, true); return; }
        failure(request, response, 500, "We could not complete this request. Reference: " + reference);
    }
    private static void redirect(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        response.setStatus(303); response.setHeader("Location", request.getContextPath() + path);
    }
}
