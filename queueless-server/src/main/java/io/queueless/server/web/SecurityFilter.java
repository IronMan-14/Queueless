package io.queueless.server.web;

import io.queueless.contracts.Api;
import io.queueless.server.service.AttemptLimiter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded input and browser defenses shared by page and JSON controllers. */
public final class SecurityFilter implements Filter {
    private static final int MAX_BODY = 64 * 1024;
    private final AttemptLimiter loginAttempts = new AttemptLimiter(java.time.Clock.systemUTC(), 60, 300_000);
    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain) throws IOException, ServletException {
        var request = (HttpServletRequest) input; var response = (HttpServletResponse) output;
        request.setCharacterEncoding("UTF-8"); response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
        if (request.isSecure() || publicOrigin().startsWith("https://")) response.setHeader("Strict-Transport-Security", "max-age=31536000");
        if (request.getMethod().equals("POST")) {
            String route = request.getServletPath() + Objects.toString(request.getPathInfo(), "");
            if (Set.of("/login", "/register", "/api/session", "/api/register").contains(route) && !loginAttempts.allow(request.getRemoteAddr())) {
                response.setHeader("Retry-After", "300");
                reject(request, response, 429, "RATE_LIMIT", "Too many sign-in attempts. Please try again in five minutes."); return;
            }
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.equals(expectedOrigin(request))) { reject(request, response, 403, "ORIGIN", "This request origin is not permitted."); return; }
            if (request.getContentLengthLong() > MAX_BODY) { reject(request, response, 413, "TOO_LARGE", "Request body is too large."); return; }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) { reject(request, response, 413, "TOO_LARGE", "Request body is too large."); return; }
            try { request = new BufferedRequest(request, body); }
            catch (IllegalArgumentException invalid) { reject(request, response, 400, "INVALID_INPUT", "The request encoding is invalid."); return; }
        }
        chain.doFilter(request, response);
    }
    private static String publicOrigin() { return System.getenv().getOrDefault("QUEUELESS_PUBLIC_ORIGIN", "").replaceAll("/$", ""); }
    private static String expectedOrigin(HttpServletRequest request) {
        if (!publicOrigin().isBlank()) return publicOrigin();
        int port = request.getServerPort();
        return request.getScheme() + "://" + request.getServerName() + ((port == 80 && !request.isSecure()) || (port == 443 && request.isSecure()) ? "" : ":" + port);
    }
    private static void reject(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        if (request.getServletPath().startsWith("/api") || Objects.toString(request.getHeader("Accept"), "").toLowerCase(Locale.ROOT).contains("application/json")) {
            response.setContentType("application/json");
            WebSupport.app(request).json().writeValue(response.getOutputStream(), new Api.Problem(code, message, false));
        } else { response.setContentType("text/plain"); response.getWriter().write(message); }
    }
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final Map<String, String[]> parameters;
        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request); this.body = body;
            var values = new LinkedHashMap<String, List<String>>();
            parse(request.getQueryString(), values);
            if (request.getContentType() != null && request.getContentType().startsWith("application/x-www-form-urlencoded")) parse(new String(body, StandardCharsets.UTF_8), values);
            var flattened = new LinkedHashMap<String, String[]>();
            values.forEach((key, entries) -> flattened.put(key, entries.toArray(String[]::new)));
            parameters = Collections.unmodifiableMap(flattened);
        }
        private static void parse(String text, Map<String, List<String>> values) {
            if (text == null || text.isBlank()) return;
            for (String pair : text.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
        }
        @Override public String getParameter(String name) { var values = parameters.get(name); return values == null || values.length == 0 ? null : values[0]; }
        @Override public Map<String, String[]> getParameterMap() {
            var copy = new LinkedHashMap<String, String[]>(); parameters.forEach((key, values) -> copy.put(key, values.clone())); return Collections.unmodifiableMap(copy);
        }
        @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(parameters.keySet()); }
        @Override public String[] getParameterValues(String name) { var values = parameters.get(name); return values == null ? null : values.clone(); }
        @Override public ServletInputStream getInputStream() {
            var stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public boolean isFinished() { return stream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Asynchronous input is not used."); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
