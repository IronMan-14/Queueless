package io.queueless.server.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.queueless.contracts.Api;
import io.queueless.server.service.AppException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/** JSON adapter only: all application rules remain in shared services. */
public final class ApiServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException { handle(request, response, false); }
    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException { handle(request, response, true); }
    private void handle(HttpServletRequest request, HttpServletResponse response, boolean mutation) throws IOException {
        var app = WebSupport.app(request); response.setContentType("application/json");
        try {
            String path = request.getPathInfo() == null ? "" : request.getPathInfo();
            if (mutation) WebSupport.checkCsrf(request, request.getHeader("X-CSRF-Token"));
            Object result;
            if (!mutation && path.equals("/health")) {
                boolean healthy;
                try { healthy = app.health(); } catch (java.sql.SQLException unavailable) { healthy = false; }
                if (!healthy) response.setStatus(503);
                result = java.util.Map.of("status", healthy ? "ok" : "unavailable");
            }
            else if (!mutation && path.equals("/session")) result = new Api.Session(WebSupport.current(request), WebSupport.csrf(request.getSession(true)));
            else if (mutation && path.equals("/session")) result = WebSupport.signIn(request, app.auth().login(read(request, Api.Login.class)));
            else if (mutation && path.equals("/register")) { result = WebSupport.signIn(request, app.auth().register(read(request, Api.Registration.class))); response.setStatus(201); }
            else if (mutation && path.equals("/logout")) { var session = request.getSession(false); if (session != null) session.invalidate(); response.setStatus(204); return; }
            else {
                var user = WebSupport.requireUser(request);
                if (!mutation && path.equals("/snapshot")) result = app.queries().snapshot(user, WebSupport.number(request.getParameter("serviceId"), 0));
                else if (!mutation && path.equals("/activity")) {
                    LocalDate to = date(request.getParameter("to"), LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
                    LocalDate from = date(request.getParameter("from"), to.minusDays(29));
                    result = app.queries().activity(user, WebSupport.number(request.getParameter("serviceId"), 0), from, to);
                }
                else if (!mutation && (path.equals("/history") || path.equals("/appointment-history"))) {
                    long service = WebSupport.number(request.getParameter("serviceId"), 0);
                    long before = WebSupport.number(request.getParameter("beforeId"), 0);
                    long limit = WebSupport.number(request.getParameter("limit"), 50);
                    if (limit < 1 || limit > 100) throw new AppException(400, "INVALID_PAGE", "Choose a page size from 1 to 100.");
                    result = path.equals("/history") ? app.queries().history(user, service, before, (int)limit) : app.queries().appointmentHistory(user, service, before, (int)limit);
                }
                else if (mutation && path.equals("/commands")) result = app.queue().execute(user, request.getSession().getId(), read(request, Api.Command.class));
                else if (!mutation && (path.equals("/reports") || path.equals("/reports.csv"))) {
                    LocalDate to = date(request.getParameter("to"), LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
                    LocalDate from = date(request.getParameter("from"), to.minusDays(29));
                    if (from.isAfter(to) || from.plusYears(1).isBefore(to)) throw new AppException(400, "INVALID_INPUT", "Choose a date range of at most one year.");
                    boolean defaultRange = (request.getParameter("from") == null || request.getParameter("from").isBlank()) && (request.getParameter("to") == null || request.getParameter("to").isBlank());
                    var rows = defaultRange ? app.queries().reports(user) : app.queries().reports(user, from, to);
                    if (path.endsWith(".csv")) { csv(response, rows); return; }
                    result = rows;
                } else throw new AppException(404, "NOT_FOUND", "This endpoint does not exist.");
            }
            app.json().writeValue(response.getOutputStream(), result);
        } catch (AppException error) { problem(request, response, error.status(), error.code(), error.getMessage(), error.status() == 503); }
        catch (JsonProcessingException | DateTimeParseException error) { problem(request, response, 400, "INVALID_INPUT", "The request contains invalid data.", false); }
        catch (Exception error) {
            String reference = UUID.randomUUID().toString(); getServletContext().log("Request failure " + reference, error);
            problem(request, response, 500, "SERVER_ERROR", "The request could not be completed. Reference: " + reference, true);
        }
    }
    private <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw new AppException(415, "CONTENT_TYPE", "Send an application/json body.");
        return WebSupport.app(request).json().readerFor(type).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(request.getInputStream());
    }
    static LocalDate date(String value, LocalDate fallback) { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
    private void problem(HttpServletRequest request, HttpServletResponse response, int status, String code, String message, boolean retryable) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer(); response.setStatus(status); response.setContentType("application/json");
        WebSupport.app(request).json().writeValue(response.getOutputStream(), new Api.Problem(code, message, retryable));
    }
    private static void csv(HttpServletResponse response, List<Api.Report> rows) throws IOException {
        response.setContentType("text/csv; charset=UTF-8"); response.setHeader("Content-Disposition", "attachment; filename=queueless-report.csv");
        var out = response.getWriter(); out.println("service_id,service,completed,cancelled,no_shows,average_wait_minutes,average_service_minutes,appointments,fulfilled_appointments");
        for (var row : rows) out.println(row.serviceId() + "," + cell(row.serviceName()) + "," + row.completed() + "," + row.cancelled() + "," + row.noShows() + "," + value(row.averageWaitMinutes()) + "," + value(row.averageServiceMinutes()) + "," + row.appointments() + "," + row.fulfilledAppointments());
    }
    private static String value(Double value) { return value == null ? "" : Double.toString(value); }
    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r\n".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
