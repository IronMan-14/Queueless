package io.queueless.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.queueless.contracts.Api;
import io.queueless.server.db.Sql;
import io.queueless.server.service.AuthService;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** CSV is exercised through the actual Servlet, authorization and JDBC query. */
@EnabledIfEnvironmentVariable(named = "QUEUELESS_TEST_DB_URL", matches = ".+")
class CsvExportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static HikariDataSource db;
    private static Tomcat tomcat;
    private static String base, previousUrl;
    private TestSupport.Fixture fixture;
    private String password;

    @BeforeAll static void start() throws Exception {
        db = TestSupport.database();
        previousUrl = System.getProperty("queueless.db.url");
        System.setProperty("queueless.db.url", System.getenv("QUEUELESS_TEST_DB_URL"));
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("queueless-csv-test-").toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addWebapp("/queueless", Path.of("src/main/webapp").toAbsolutePath().toString());
        context.setParentClassLoader(CsvExportTest.class.getClassLoader());
        var resources = new StandardRoot(context);
        resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", Path.of("target/classes").toAbsolutePath().toString(), "/"));
        context.setResources(resources);
        tomcat.start();
        assertTrue(context.getState().isAvailable());
        base = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/queueless";
    }

    @AfterAll static void stop() throws Exception {
        if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
        if (db != null) db.close();
        if (previousUrl == null) System.clearProperty("queueless.db.url");
        else System.setProperty("queueless.db.url", previousUrl);
    }

    @BeforeEach void fixture() throws Exception {
        fixture = TestSupport.fixture(db);
        password = "Csv-test-" + UUID.randomUUID();
        try (var c = db.getConnection()) {
            Sql.update(c, "UPDATE users SET password_hash=? WHERE id IN (?,?)", AuthService.hash(password), fixture.user().id(), fixture.staff().id());
        }
    }

    @Test void quotesCommasQuotesUnicodeAndNewlinesAndNeutralizesFormulaPrefixes() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String name = "विभाग, \"受付\"\nSecond line " + suffix;
        Map<Long, String> expectedCells = new LinkedHashMap<>();
        expectedCells.put(fixture.service(), "\"विभाग, \"\"受付\"\"\nSecond line " + suffix + "\"");
        try (var c = db.getConnection()) {
            Sql.update(c, "UPDATE services SET name=? WHERE id=?", name, fixture.service());
            for (String prefix : List.of("=", "+", "-", "@", "\t", "\r", "\n")) {
                String formula = prefix + "1+1 " + suffix;
                long service = Sql.insert(c, "INSERT INTO services(name,description) VALUES(?,'CSV fixture')", formula);
                Sql.update(c, "INSERT INTO staff_assignments(user_id,service_id) VALUES(?,?)", fixture.staff().id(), service);
                expectedCells.put(service, "\"'" + formula + "\"");
            }
        }
        var response = get(signIn(fixture.staff()), "/api/reports.csv?from=2026-02-01&to=2026-02-01");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).replace(" ", "").contains("text/csv;charset=utf-8"));
        assertEquals("attachment; filename=queueless-report.csv", response.headers().firstValue("Content-Disposition").orElse(""));
        assertTrue(response.body().startsWith("service_id,service,completed,cancelled,no_shows,average_wait_minutes,average_service_minutes,appointments,fulfilled_appointments"));
        for (var cell : expectedCells.entrySet()) {
            assertTrue(response.body().contains(cell.getKey() + "," + cell.getValue() + ",0,0,0,,,0,0"),
                    "CSV must preserve the text and blank means while safely quoting the cell: " + cell.getKey());
        }
    }

    @Test void reportDatesUseInclusiveLocalDaysAndOnlyAuthorizedServices() throws Exception {
        Instant start = LocalDate.of(2026, 2, 1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        Instant end = start.plus(Duration.ofDays(1));
        String serviceName;
        var foreign = TestSupport.fixture(db);
        try (var c = db.getConnection()) {
            serviceName = Sql.one(c, "SELECT name FROM services WHERE id=?", r -> r.getString(1), fixture.service()).orElseThrow();
            Sql.update(c, "INSERT INTO queue_sessions(service_id,business_date,last_token) VALUES(?,'2026-02-01',4)", fixture.service());
            int token = 0;
            for (Instant finished : List.of(start.minusSeconds(1), start, end.minusSeconds(1), end)) {
                token++;
                Sql.insert(c, """
                        INSERT INTO queue_entries(service_id,user_id,business_date,token_number,state,ready_sequence,
                          eligible_at,created_at,started_at,finished_at)
                        VALUES(?,?,'2026-02-01',?,'COMPLETED',?,?,?,?,?)
                        """, fixture.service(), fixture.user().id(), token, token, finished.minusSeconds(300),
                        finished.minusSeconds(300), finished.minusSeconds(180), finished);
            }
        }
        var staff = signIn(fixture.staff());
        var response = get(staff, "/api/reports.csv?from=2026-02-01&to=2026-02-01&serviceId=" + foreign.service());
        assertEquals(200, response.statusCode(), response.body());
        var lines = response.body().lines().toList();
        assertEquals(2, lines.size(), "An unassigned service cannot be added by a query parameter.");
        assertEquals(fixture.service() + ",\"" + serviceName + "\",2,0,0,2.0,3.0,0,0", lines.get(1));
        assertEquals(401, get(client(), "/api/reports.csv").statusCode());
        assertEquals(403, get(signIn(fixture.user()), "/api/reports.csv").statusCode());
        for (String query : List.of("from=2026-02-02&to=2026-02-01", "from=2026-02-30&to=2026-03-01", "from=2025-01-01&to=2026-01-02")) {
            var invalid = get(staff, "/api/reports.csv?" + query);
            assertEquals(400, invalid.statusCode());
            assertTrue(invalid.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    private HttpClient signIn(Api.User user) throws Exception {
        var client = client();
        var anonymous = JSON.readValue(get(client, "/api/session").body(), Api.Session.class);
        String email;
        try (var c = db.getConnection()) {
            email = Sql.one(c, "SELECT email FROM users WHERE id=?", r -> r.getString(1), user.id()).orElseThrow();
        }
        var request = HttpRequest.newBuilder(URI.create(base + "/api/session")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("X-CSRF-Token", anonymous.csrfToken())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(new Api.Login(email, password)))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return client;
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
