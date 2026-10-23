package io.queueless.server.web;

import io.queueless.contracts.Api;
import io.queueless.server.AppContext;
import io.queueless.server.service.AppException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;

final class WebSupport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private WebSupport() { }
    static AppContext app(HttpServletRequest request) {
        return (AppContext) request.getServletContext().getAttribute("app");
    }
    static Api.User current(HttpServletRequest request) throws SQLException {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute("userId") instanceof Long id)) return null;
        try { return app(request).auth().current(id); }
        catch (AppException error) {
            if (error.status() == 401 || error.status() == 403) { session.invalidate(); return null; }
            throw error;
        }
    }
    static Api.User requireUser(HttpServletRequest request) throws SQLException {
        var user = current(request);
        if (user == null) throw new AppException(401, "AUTHENTICATION", "Please sign in to continue.");
        return user;
    }
    static String csrf(HttpSession session) {
        synchronized (session) {
            var existing = (String) session.getAttribute("csrf");
            if (existing != null) return existing;
            byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute("csrf", value); return value;
        }
    }
    static void checkCsrf(HttpServletRequest request, String supplied) {
        var session = request.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute("csrf");
        if (expected == null || supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new AppException(403, "CSRF", "This form has expired. Refresh the page and try again.");
    }
    static Api.Session signIn(HttpServletRequest request, Api.User user) {
        request.getSession(true);
        request.changeSessionId();
        var session = request.getSession();
        session.setAttribute("userId", user.id());
        session.removeAttribute("csrf");
        return new Api.Session(user, csrf(session));
    }
    static long number(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { long parsed = Long.parseLong(value); if (parsed < 0) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException error) { throw new AppException(400, "INVALID_INPUT", "A valid numeric identifier is required."); }
    }
}
