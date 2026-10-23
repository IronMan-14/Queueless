package io.queueless.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.queueless.contracts.Api;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String address;
    private ExecutorService serverWorkers;
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newCachedThreadPool();
        server.setExecutor(serverWorkers);
        address = "http://127.0.0.1:" + server.getAddress().getPort() + "/queueless";
        server.start();
    }

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (serverWorkers != null) serverWorkers.shutdownNow();
        assertNull(serverFailure.get(), "Mock protocol assertions failed: " + serverFailure.get());
    }

    @Test void obtainsAnonymousCsrfRotatesSessionAndSendsSameCommandOnExplicitRetry() throws Exception {
        List<String> requestIds = new ArrayList<>();
        endpoint("/api/session", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=anonymous; Path=/queueless; HttpOnly");
                respond(exchange, 200, new Api.Session(null, "anonymous-csrf"));
            } else {
                assertEquals("anonymous-csrf", exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
                assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("anonymous"));
                Api.Login login = json.readValue(exchange.getRequestBody(), Api.Login.class);
                assertEquals("operator@example.test", login.email());
                assertEquals("not-a-real-secret", login.password());
                exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=authenticated; Path=/queueless; HttpOnly");
                respond(exchange, 200, authenticated());
            }
        });
        endpoint("/api/commands", exchange -> {
            assertEquals("authenticated-csrf", exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
            assertTrue(exchange.getRequestHeaders().getFirst("Cookie").contains("authenticated"));
            Api.Command command = json.readValue(exchange.getRequestBody(), Api.Command.class);
            requestIds.add(command.requestId());
            if (requestIds.size() == 1) respond(exchange, 503, new Api.Problem("RETRY", "Try this request again.", true));
            else respond(exchange, 200, new Api.Result(12, 4, "Counter updated.", true));
        });
        HttpApiClient client = new HttpApiClient(address);
        assertEquals("STAFF", client.login("operator@example.test", "not-a-real-secret").user().role());
        Api.Command command = new Api.Command(UUID.randomUUID().toString(), "CLAIM", 5, 0, 12, 0, 3, Map.of());
        ApiException first = assertThrows(ApiException.class, () -> client.command(command));
        assertTrue(first.retryable());
        assertEquals(1, requestIds.size(), "Mutations must never retry invisibly.");
        assertTrue(client.command(command).replayed());
        assertEquals(List.of(command.requestId(), command.requestId()), requestIds);
    }

    @Test void sessionExpiryClearsCookieAndRejectsFurtherMutationsLocally() throws Exception {
        loginEndpoint();
        AtomicBoolean called = new AtomicBoolean();
        endpoint("/api/snapshot", exchange -> respond(exchange, 401, new Api.Problem("UNAUTHORIZED", "Sign in again.", false)));
        endpoint("/api/commands", exchange -> { called.set(true); respond(exchange, 500, Map.of()); });
        HttpApiClient client = new HttpApiClient(address);
        client.login("operator@example.test", "not-a-real-secret");
        assertEquals(401, assertThrows(ApiException.class, () -> client.snapshot(1)).status());
        assertNull(client.session());
        assertEquals(401, assertThrows(ApiException.class, () -> client.command(new Api.Command("id", "NEXT", 1, 0, 1, 0, 1, Map.of()))).status());
        assertFalse(called.get());
    }

    @Test void malformedErrorBodyIsNotShownAsServerInternalsAndLogoutAlwaysClearsSession() throws Exception {
        loginEndpoint();
        endpoint("/api/logout", exchange -> {
            assertEquals("authenticated-csrf", exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
            byte[] body = "<html>SQL password=do-not-display</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
        });
        HttpApiClient client = new HttpApiClient(address);
        client.login("operator@example.test", "not-a-real-secret");
        ApiException error = assertThrows(ApiException.class, client::logout);
        assertFalse(error.getMessage().contains("password"));
        assertNull(client.session());
    }

    @Test void acceptsHttpsAndLoopbackButRejectsCredentialUrlsInsecureRemoteAndRedirects() throws Exception {
        assertDoesNotThrow(() -> new HttpApiClient("https://queue.example.test/app"));
        assertDoesNotThrow(() -> new HttpApiClient("http://localhost:8080/app"));
        assertDoesNotThrow(() -> new HttpApiClient("http://[::1]:8080/app"));
        for (String invalid : List.of("http://queue.example.test/app", "http://localhost.evil.test/app",
                "https://user:password@queue.example.test/app", "https://queue.example.test/app?secret=x", "file:///test")) {
            assertThrows(IllegalArgumentException.class, () -> new HttpApiClient(invalid));
        }
        AtomicBoolean redirected = new AtomicBoolean();
        endpoint("/api/session", exchange -> {
            exchange.getResponseHeaders().add("Location", address + "/other");
            respond(exchange, 302, Map.of());
        });
        endpoint("/other", exchange -> { redirected.set(true); respond(exchange, 200, authenticated()); });
        assertEquals(302, assertThrows(ApiException.class, () -> new HttpApiClient(address).currentSession()).status());
        assertFalse(redirected.get());
    }

    private void loginEndpoint() {
        endpoint("/api/session", exchange -> respond(exchange, 200,
                "GET".equals(exchange.getRequestMethod()) ? new Api.Session(null, "anonymous-csrf") : authenticated()));
    }

    @Test void preservesTypedHistoryRecordsAndStableCursor() throws Exception {
        endpoint("/api/history", exchange -> {
            assertEquals("serviceId=5&beforeId=92&limit=50", exchange.getRequestURI().getQuery());
            var row = new Api.Visit(91, 5, 7, "2026-10-11/5-1", "COMPLETED", "WALK_IN", "W", 3,
                    12L, "2026-10-11T05:00:00Z", "2026-10-11T05:00:00Z", null, null);
            respond(exchange, 200, new Api.Page<>(List.of(row), 91));
        });
        Api.Page<Api.Visit> page = new HttpApiClient(address).visitHistory(5, 92, 50);
        assertEquals(91, page.nextBeforeId());
        assertEquals("COMPLETED", page.items().getFirst().state());
        assertEquals(12L, page.items().getFirst().counterId());
    }

    @Test void timeoutThenExpiryAndSameActorLoginReplaysExactCommandWithNewCsrf() throws Exception {
        AtomicInteger loginCount = new AtomicInteger(), commandCount = new AtomicInteger();
        AtomicReference<Api.Command> committed = new AtomicReference<>();
        CountDownLatch releaseTimedOutRequest = new CountDownLatch(1), timeoutHandlerFinished = new CountDownLatch(1);
        endpoint("/api/session", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, new Api.Session(null, "anonymous-csrf"));
            } else {
                assertEquals("anonymous-csrf", exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
                respond(exchange, 200, new Api.Session(new Api.User(7, "Test operator", "STAFF"), "login-" + loginCount.incrementAndGet()));
            }
        });
        endpoint("/api/commands", exchange -> {
            Api.Command request = json.readValue(exchange.getRequestBody(), Api.Command.class);
            if (commandCount.incrementAndGet() == 1) {
                assertEquals("login-1", exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
                committed.set(request); // Simulate a committed transaction whose response never reaches the client.
                try { assertTrue(releaseTimedOutRequest.await(5, TimeUnit.SECONDS)); }
                finally { timeoutHandlerFinished.countDown(); }
            } else {
                assertEquals(committed.get(), request, "Recovery must preserve the complete request, not only its UUID.");
                assertEquals("login-2", exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
                respond(exchange, 200, new Api.Result(12, 4, "Recovered committed claim.", true));
            }
        });
        endpoint("/api/snapshot", exchange -> respond(exchange, 401, new Api.Problem("SESSION_EXPIRED", "Sign in again.", false)));
        HttpApiClient client = new HttpApiClient(address, Duration.ofMillis(400));
        Api.Command original = new Api.Command(UUID.randomUUID().toString(), "CLAIM", 5, 0, 12, 0, 3, Map.of("reason", "test recovery"));
        try {
            assertEquals(7, client.login("operator@example.test", "not-a-real-secret").user().id());
            assertThrows(java.net.http.HttpTimeoutException.class, () -> client.command(original));
            assertEquals(1, commandCount.get());
            assertEquals(401, assertThrows(ApiException.class, () -> client.snapshot(5)).status());
            assertNull(client.session());
            assertEquals(7, client.login("operator@example.test", "not-a-real-secret").user().id());
            assertTrue(client.command(original).replayed());
            assertEquals(2, commandCount.get());
        } finally {
            releaseTimedOutRequest.countDown();
            assertTrue(timeoutHandlerFinished.await(5, TimeUnit.SECONDS));
        }
    }

    private Api.Session authenticated() { return new Api.Session(new Api.User(7, "Test operator", "STAFF"), "authenticated-csrf"); }

    private void endpoint(String path, Handler handler) {
        server.createContext("/queueless" + path, exchange -> {
            try { handler.handle(exchange); }
            catch (Throwable failure) {
                serverFailure.compareAndSet(null, failure);
                respond(exchange, 500, new Api.Problem("TEST_FAILURE", "Test server failure.", false));
            } finally { exchange.close(); }
        });
    }

    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }
}
