package io.queueless.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queueless.contracts.Api;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** HTTP and session boundary. No database credentials or password persistence. */
public final class HttpApiClient {
    private final URI baseUri;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient http;
    private final Duration requestTimeout;
    private final ObjectMapper json = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private volatile Api.Session session;

    public HttpApiClient(String address) {
        this(address, Duration.ofSeconds(25));
    }

    HttpApiClient(String address, Duration requestTimeout) {
        baseUri = validateAddress(address);
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Request timeout must be positive.");
        }
        this.requestTimeout = requestTimeout;
        http = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static URI validateAddress(String address) {
        URI parsed;
        try { parsed = URI.create(address.trim()); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Enter a valid backend URL."); }
        String host = parsed.getHost();
        if (host == null || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("The backend URL must have a host and no credentials, query or fragment.");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean loopback = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(normalizedHost);
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                && !("http".equalsIgnoreCase(parsed.getScheme()) && loopback)) {
            throw new IllegalArgumentException("Use HTTPS for the backend. HTTP is permitted only on localhost.");
        }
        String normalized = parsed.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }

    public Api.Session login(String email, String password) throws IOException, InterruptedException, ApiException {
        currentSession(); // Establish anonymous cookie and CSRF token before authenticating.
        session = request("POST", "api/session", new Api.Login(email, password), true, Api.Session.class);
        if (session == null || session.user() == null || session.csrfToken() == null || session.csrfToken().isBlank()) {
            clearSession();
            throw new IOException("The backend returned an incomplete session.");
        }
        return session;
    }

    public Api.Session currentSession() throws IOException, InterruptedException, ApiException {
        session = request("GET", "api/session", null, false, Api.Session.class);
        return session;
    }

    public Api.Snapshot snapshot(long serviceId) throws IOException, InterruptedException, ApiException {
        return request("GET", "api/snapshot?serviceId=" + serviceId, null, false, Api.Snapshot.class);
    }

    public Api.Result command(Api.Command command) throws IOException, InterruptedException, ApiException {
        return request("POST", "api/commands", command, true, Api.Result.class);
    }

    public List<Api.Report> reports(LocalDate from, LocalDate to) throws IOException, InterruptedException, ApiException {
        return Arrays.asList(request("GET", reportPath("api/reports", from, to), null, false, Api.Report[].class));
    }

    public Api.Page<Api.Visit> visitHistory(long serviceId, long beforeId, int limit)
            throws IOException, InterruptedException, ApiException {
        return history("api/history", serviceId, beforeId, limit, Api.Visit.class);
    }

    public Api.Page<Api.Appointment> appointmentHistory(long serviceId, long beforeId, int limit)
            throws IOException, InterruptedException, ApiException {
        return history("api/appointment-history", serviceId, beforeId, limit, Api.Appointment.class);
    }

    private <T> Api.Page<T> history(String path, long serviceId, long beforeId, int limit, Class<T> type)
            throws IOException, InterruptedException, ApiException {
        if (serviceId <= 0 || beforeId < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Choose a service and a valid history page.");
        }
        String query = path + "?serviceId=" + serviceId + "&beforeId=" + beforeId + "&limit=" + limit;
        return json.readValue(send("GET", query, null, false).body(),
                json.getTypeFactory().constructParametricType(Api.Page.class, type));
    }

    public byte[] reportCsv(LocalDate from, LocalDate to) throws IOException, InterruptedException, ApiException {
        return send("GET", reportPath("api/reports.csv", from, to), null, false).body();
    }

    private String reportPath(String path, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Choose a valid report date range.");
        }
        return path + "?from=" + from + "&to=" + to;
    }

    public void logout() throws IOException, InterruptedException, ApiException {
        try { send("POST", "api/logout", null, true); }
        finally { clearSession(); }
    }

    public void clearSession() {
        session = null;
        cookies.getCookieStore().removeAll();
    }

    public Api.Session session() { return session; }

    private <T> T request(String method, String path, Object body, boolean csrf, Class<T> type)
            throws IOException, InterruptedException, ApiException {
        return json.readValue(send(method, path, body, csrf).body(), type);
    }

    private HttpResponse<byte[]> send(String method, String path, Object body, boolean csrf)
            throws IOException, InterruptedException, ApiException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout).header("Accept", "application/json");
        if (csrf) {
            Api.Session current = session;
            if (current == null || current.csrfToken() == null) {
                throw new ApiException(401, "SESSION_REQUIRED", "Sign in again to continue.", false);
            }
            builder.header("X-CSRF-Token", current.csrfToken());
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 401) clearSession();
            Api.Problem problem;
            try { problem = json.readValue(response.body(), Api.Problem.class); }
            catch (JsonProcessingException malformed) { problem = null; }
            String message = problem != null && problem.message() != null ? problem.message()
                    : "The backend could not complete this request (HTTP " + response.statusCode() + ").";
            throw new ApiException(response.statusCode(), problem == null ? "HTTP_ERROR" : problem.code(),
                    message, problem != null && problem.retryable());
        }
        return response;
    }
}
