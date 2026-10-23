package io.queueless.desktop;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CsvDownloadTest {
    private HttpServer server;
    private HttpApiClient client;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new HttpApiClient("http://127.0.0.1:" + server.getAddress().getPort() + "/queueless");
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void preservesExactDownloadedBytesAndSelectedDateRange() throws Exception {
        byte[] expected = ("\uFEFFservice_id,service,completed\r\n7,\"विभाग, \"\"受付\"\"\nSecond line\",2\r\n"
                + "8,\"'=1+1\",0\r\n").getBytes(StandardCharsets.UTF_8);
        var query = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        server.createContext("/queueless/api/reports.csv", exchange -> {
            try {
                query.set(exchange.getRequestURI().getRawQuery());
                method.set(exchange.getRequestMethod());
                exchange.getResponseHeaders().add("Content-Type", "text/csv; charset=UTF-8");
                exchange.sendResponseHeaders(200, expected.length);
                exchange.getResponseBody().write(expected);
            } finally { exchange.close(); }
        });
        assertArrayEquals(expected, client.reportCsv(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                "Downloads must not re-encode CSV, strip a BOM, or normalize embedded newlines.");
        assertEquals("GET", method.get());
        assertEquals("from=2026-02-01&to=2026-02-28", query.get());
    }

    @Test void rejectsInvalidRangesLocallyAndDoesNotSaveServerErrorsAsCsv() throws Exception {
        var calls = new AtomicInteger();
        server.createContext("/queueless/api/reports.csv", exchange -> {
            try {
                calls.incrementAndGet();
                byte[] body = "{\"code\":\"FORBIDDEN\",\"message\":\"Staff access is required.\",\"retryable\":false}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(403, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        LocalDate day = LocalDate.of(2026, 2, 1);
        assertThrows(IllegalArgumentException.class, () -> client.reportCsv(null, day));
        assertThrows(IllegalArgumentException.class, () -> client.reportCsv(day, null));
        assertThrows(IllegalArgumentException.class, () -> client.reportCsv(day.plusDays(1), day));
        assertEquals(0, calls.get());
        ApiException denied = assertThrows(ApiException.class, () -> client.reportCsv(day, day));
        assertEquals(403, denied.status());
        assertFalse(denied.retryable());
        assertEquals(1, calls.get());
    }
}
