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
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static io.queueless.server.integration.TestSupport.*;

/** Actual HTTP, cookies, Servlet dispatch, JSP compilation and MySQL persistence. */
@EnabledIfEnvironmentVariable(named="QUEUELESS_TEST_DB_URL", matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebIntegrationTest {
    static HikariDataSource db;
    static Tomcat tomcat;
    static String base;
    static final ObjectMapper JSON = new ObjectMapper();
    static String previousUrl;
    Fixture fixture;
    String password;
    @BeforeAll static void start() throws Exception {
        db = database(); // Refuses any database without the dedicated _test suffix.
        previousUrl = System.getProperty("queueless.db.url");
        System.setProperty("queueless.db.url", System.getenv("QUEUELESS_TEST_DB_URL"));
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("queueless-web-test-").toString());
        tomcat.setPort(0); tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addWebapp("/queueless", Path.of("src/main/webapp").toAbsolutePath().toString());
        context.setParentClassLoader(WebIntegrationTest.class.getClassLoader());
        var resources = new StandardRoot(context);
        resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", Path.of("target/classes").toAbsolutePath().toString(), "/"));
        context.setResources(resources);
        tomcat.start();
        assertTrue(context.getState().isAvailable(), "The real web application must start.");
        base = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/queueless";
    }
    @AfterAll static void stop() throws Exception {
        if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
        if (db != null) db.close();
        if (previousUrl == null) System.clearProperty("queueless.db.url"); else System.setProperty("queueless.db.url", previousUrl);
    }
    @BeforeEach void fixture() throws Exception {
        fixture = TestSupport.fixture(db); password = "Web-test-" + UUID.randomUUID();
        String hash = AuthService.hash(password);
        try (var c = db.getConnection()) {
            Sql.update(c, "UPDATE users SET password_hash=? WHERE id IN (?,?,?)", hash, fixture.user().id(), fixture.staff().id(), fixture.admin().id());
        }
    }
    @Test @Order(1) void browserRegistrationAndFormsCompileJspAndPersistRealJoin() throws Exception {
        var client = client();
        var login = get(client, "/register");
        assertEquals(200, login.statusCode(), login.body());
        assertTrue(login.headers().firstValue("Content-Security-Policy").isPresent());
        String token = csrf(login.body());
        var registration = form(client, "/register", Map.of("csrf", token, "name", "<script>visitor</script>", "email", UUID.randomUUID() + "@example.test", "password", password, "role", "ADMIN"));
        assertEquals(200, registration.statusCode(), registration.body());
        assertTrue(registration.body().contains("&lt;script&gt;visitor&lt;/script&gt;"));
        assertFalse(registration.body().contains("<script>visitor</script>"));
        assertTrue(registration.body().contains("Your account is ready."));
        assertFalse(get(client, "/app?serviceId=" + fixture.service()).body().contains("Your account is ready."));
        var session = JSON.readValue(get(client, "/api/session").body(), Api.Session.class);
        assertEquals("USER", session.user().role());
        assertNotEquals(token, session.csrfToken());
        var dashboard = get(client, "/app?serviceId=" + fixture.service());
        assertEquals(200, dashboard.statusCode(), dashboard.body());
        String requestId = UUID.randomUUID().toString();
        var body = new LinkedHashMap<String, String>();
        body.put("csrf", csrf(dashboard.body())); body.put("requestId", requestId); body.put("operation", "JOIN"); body.put("serviceId", Long.toString(fixture.service()));
        var join = form(client, "/command", body);
        assertEquals(200, join.statusCode(), join.body());
        assertTrue(join.body().contains("Token "));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE user_id=? AND service_id=?", session.user().id(), fixture.service()));
        var replay = form(client, "/command", body);
        assertEquals(200, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains("Previously completed request"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE user_id=? AND service_id=?", session.user().id(), fixture.service()));
    }
    @Test @Order(2) void authenticatedJsonWorkflowEnforcesCsrfRolesVersionsAndSessionInvalidation() throws Exception {
        var visitor = client(); var operator = client();
        var anonymous = JSON.readValue(get(visitor, "/api/session").body(), Api.Session.class);
        assertNull(anonymous.user());
        assertEquals(403, json(visitor, "/api/session", new Api.Login(email(fixture.user()), password), null).statusCode());
        var user = signIn(visitor, fixture.user()); var staff = signIn(operator, fixture.staff());
        assertEquals(403, get(visitor, "/api/reports").statusCode());
        assertEquals(403, json(visitor, "/api/commands", command("JOIN", fixture.service(), 0, 0, 0, 0, Map.of()), "wrong").statusCode());
        var joined = result(json(visitor, "/api/commands", command("JOIN", fixture.service(), 0, 0, 0, 0, Map.of()), user.csrfToken()));
        assertEquals(403, json(visitor, "/api/commands", command("CLAIM", fixture.service(), 0, fixture.counter(), 0, 0, Map.of()), user.csrfToken()).statusCode());
        var claim = result(json(operator, "/api/commands", command("CLAIM", fixture.service(), 0, fixture.counter(), 0, 0, Map.of()), staff.csrfToken()));
        var call = result(json(operator, "/api/commands", command("NEXT", fixture.service(), 0, fixture.counter(), 0, claim.version(), Map.of()), staff.csrfToken()));
        assertEquals(joined.entityId(), call.entityId());
        var start = result(json(operator, "/api/commands", command("START", fixture.service(), call.entityId(), fixture.counter(), call.version(), claim.version(), Map.of()), staff.csrfToken()));
        assertEquals(409, json(operator, "/api/commands", command("COMPLETE", fixture.service(), call.entityId(), fixture.counter(), call.version(), claim.version(), Map.of()), staff.csrfToken()).statusCode());
        result(json(operator, "/api/commands", command("COMPLETE", fixture.service(), call.entityId(), fixture.counter(), start.version(), claim.version(), Map.of()), staff.csrfToken()));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE id=? AND state='COMPLETED'", joined.entityId()));
        assertEquals(0, count(db, "SELECT COUNT(*) FROM command_receipts WHERE status='PENDING'"));
        var dashboard = get(operator, "/app?serviceId=" + fixture.service() + "&view=counters");
        assertEquals(200, dashboard.statusCode(), dashboard.body());
        assertTrue(dashboard.body().contains("Service counters"));
        var report = get(operator, "/api/reports.csv"); assertEquals(200, report.statusCode(), report.body()); assertTrue(report.body().startsWith("service_id,service"));
        assertEquals(204, json(visitor, "/api/logout", Map.of(), user.csrfToken()).statusCode());
        assertEquals(401, get(visitor, "/api/snapshot").statusCode());
    }
    @Test @Order(3) void administratorPageCompilesAndInvalidBodiesAreBounded() throws Exception {
        var client = client(); var admin = signIn(client, fixture.admin());
        var page = get(client, "/app?serviceId=" + fixture.service() + "&view=administration");
        assertEquals(200, page.statusCode(), page.body()); assertTrue(page.body().contains("Manage your services"));
        assertTrue(page.body().contains("Registered accounts (latest 500)"));
        assertTrue(page.body().contains("Service audit history (latest 100)"));
        assertTrue(page.body().contains("Monday"));
        assertTrue(page.body().contains("acknowledgeShortfall"));
        var report = get(client, "/reports?serviceId=" + fixture.service());
        assertEquals(200, report.statusCode(), report.body());
        assertTrue(report.body().contains("Activity by weekday and hour"));
        assertTrue(report.body().contains("start-date cohort"));
        var activity = get(client, "/api/activity?serviceId=" + fixture.service());
        assertEquals(200, activity.statusCode(), activity.body());
        assertEquals(7, JSON.readTree(activity.body()).get("completedByStartHour").size());
        assertEquals(24, JSON.readTree(activity.body()).get("completedByStartHour").get(0).size());
        var bad = client.send(HttpRequest.newBuilder(URI.create(base + "/api/commands")).header("Content-Type", "application/json").header("X-CSRF-Token", admin.csrfToken()).POST(HttpRequest.BodyPublishers.ofString("x".repeat(65537))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, bad.statusCode());
        var malformed = client.send(HttpRequest.newBuilder(URI.create(base + "/api/commands")).header("Content-Type", "application/json").header("X-CSRF-Token", admin.csrfToken()).POST(HttpRequest.BodyPublishers.ofString("{bad" )).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, malformed.statusCode());
        var evilOrigin = client.send(HttpRequest.newBuilder(URI.create(base + "/api/logout")).header("Content-Type", "application/json").header("X-CSRF-Token", admin.csrfToken()).header("Origin", "https://unrelated.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, evilOrigin.statusCode());
    }
    @Test @Order(4) void ajaxRedirectPreservesFlashAndHistoryRoutesRespectOwnership() throws Exception {
        var visitor = client();
        var health = get(visitor, "/api/health");
        assertEquals(200, health.statusCode(), health.body());
        assertEquals("ok", JSON.readTree(health.body()).get("status").asText());
        var user = signIn(visitor, fixture.user());
        String body = "csrf=" + user.csrfToken() + "&requestId=" + UUID.randomUUID() + "&operation=JOIN&serviceId=" + fixture.service();
        var response = visitor.send(HttpRequest.newBuilder(URI.create(base + "/command")).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        String destination = JSON.readTree(response.body()).get("redirect").asText();
        assertEquals("/queueless/app?serviceId=" + fixture.service() + "&view=visits", destination);
        assertTrue(JSON.readTree(response.body()).get("message").asText().contains("Your place in the queue is saved."));
        assertFalse(JSON.readTree(response.body()).get("replayed").asBoolean());
        var flash = get(visitor, destination.substring("/queueless".length()));
        assertTrue(flash.body().contains("issued."), flash.body());
        assertFalse(get(visitor, "/app?serviceId=" + fixture.service()).body().contains("issued."));
        assertEquals(204, json(visitor, "/api/logout", Map.of(), user.csrfToken()).statusCode());
        var renewed = signIn(visitor, fixture.user());
        String retriedBody = body.replace("csrf=" + user.csrfToken(), "csrf=" + renewed.csrfToken());
        var retried = visitor.send(HttpRequest.newBuilder(URI.create(base + "/command")).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(retriedBody)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, retried.statusCode(), retried.body());
        assertTrue(JSON.readTree(retried.body()).get("replayed").asBoolean());
        assertTrue(JSON.readTree(retried.body()).get("message").asText().contains("no duplicate action was taken"));
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE user_id=? AND service_id=?", fixture.user().id(), fixture.service()));
        assertTrue(get(visitor, "/app?serviceId=" + fixture.service()).body().contains("Previously completed request"));
        var history = get(visitor, "/api/history?serviceId=" + fixture.service() + "&limit=1");
        assertEquals(200, history.statusCode(), history.body());
        assertEquals(1, JSON.readTree(history.body()).get("items").size());
        assertEquals(fixture.user().id(), JSON.readTree(history.body()).get("items").get(0).get("userId").asLong());
        assertEquals(400, get(visitor, "/api/history?serviceId=" + fixture.service() + "&limit=101").statusCode());
        assertEquals(200, get(visitor, "/history?serviceId=" + fixture.service()).statusCode());
        assertEquals(200, get(visitor, "/appointment-history?serviceId=" + fixture.service()).statusCode());
        assertEquals(200, get(visitor, "/api/appointment-history?serviceId=" + fixture.service()).statusCode());
        assertEquals(403, get(visitor, "/api/activity?serviceId=" + fixture.service()).statusCode());
        var foreign = TestSupport.fixture(db);
        var staff = client(); signIn(staff, fixture.staff());
        assertEquals(403, get(staff, "/api/history?serviceId=" + foreign.service()).statusCode());
        long notification;
        try (var c = db.getConnection()) {
            long work = Sql.insert(c, "INSERT INTO notification_work(user_id,event_key,message,done) VALUES(?,?,?,TRUE)", fixture.user().id(), "web-test:" + UUID.randomUUID(), "A test visit update");
            notification = Sql.insert(c, "INSERT INTO notifications(user_id,work_id,message) VALUES(?,?,?)", fixture.user().id(), work, "A test visit update");
        }
        assertTrue(get(visitor, "/app?serviceId=" + fixture.service() + "&view=notifications").body().contains("Mark read"));
        var marked = browserCommand(visitor, renewed, "READ_NOTIFICATION", notification, 0, 0, 0, Map.of());
        assertFeedback(marked, "Notification marked as read.", "&view=notifications");
        var snapshot = JSON.readValue(get(visitor, "/api/snapshot?serviceId=" + fixture.service()).body(), Api.Snapshot.class);
        assertTrue(snapshot.notifications().stream().filter(n -> n.id() == notification).findFirst().orElseThrow().read());
    }
    @Test @Order(5) void browserCommandsReturnActionableTypedErrorsWithoutChangingState() throws Exception {
        var visitor = client(); var user = signIn(visitor, fixture.user());
        assertFeedback(browserCommand(visitor, user, "JOIN", 0, 0, 0, 0, Map.of()), "Your place in the queue is saved.", "&view=visits");
        var conflict = browserCommand(visitor, user, "JOIN", 0, 0, 0, 0, Map.of());
        assertProblem(conflict, 409, "CONFLICT");
        assertTrue(JSON.readTree(conflict.body()).get("message").asText().contains("Resolve your current visit"));
        assertProblem(browserCommand(visitor, user, "UNKNOWN", 0, 0, 0, 0, Map.of()), 400, "INVALID_OPERATION");
        assertProblem(browserCommand(visitor, new Api.Session(user.user(), "expired"), "JOIN", 0, 0, 0, 0, Map.of()), 403, "CSRF");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM queue_entries WHERE service_id=? AND user_id=?", fixture.service(), fixture.user().id()));
        var wrongOrigin = visitor.send(HttpRequest.newBuilder(URI.create(base + "/command")).header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded").header("Origin", "https://unrelated.example").POST(HttpRequest.BodyPublishers.ofString("csrf=" + user.csrfToken())).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(wrongOrigin, 403, "ORIGIN");
        var oversized = visitor.send(HttpRequest.newBuilder(URI.create(base + "/command")).header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("x".repeat(65537))).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(oversized, 413, "TOO_LARGE");
        var anonymousClient = client();
        var anonymous = JSON.readValue(get(anonymousClient, "/api/session").body(), Api.Session.class);
        assertProblem(browserCommand(anonymousClient, anonymous, "JOIN", 0, 0, 0, 0, Map.of()), 401, "AUTHENTICATION");
    }
    @Test @Order(6) void browserAppointmentFeedbackTracksReservationsCheckInAndCancellation() throws Exception {
        var visitor = client(); var user = signIn(visitor, fixture.user());
        var tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1).atTime(10, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        long first, second;
        try (var c = db.getConnection()) {
            first = Sql.insert(c, "INSERT INTO appointment_slots(service_id,starts_at,ends_at,capacity) VALUES(?,?,?,2)", fixture.service(), tomorrow, tomorrow.plusSeconds(900));
            second = Sql.insert(c, "INSERT INTO appointment_slots(service_id,starts_at,ends_at,capacity) VALUES(?,?,?,2)", fixture.service(), tomorrow.plusSeconds(3600), tomorrow.plusSeconds(4500));
        }
        assertFeedback(browserCommand(visitor, user, "BOOK", first, 0, 0, 0, Map.of()), "Appointment booked.", "&view=appointments");
        long appointment;
        try (var c = db.getConnection()) { appointment = Sql.one(c, "SELECT id FROM appointments WHERE user_id=? AND service_id=?", r -> r.getLong(1), fixture.user().id(), fixture.service()).orElseThrow(); }
        assertFeedback(browserCommand(visitor, user, "RESCHEDULE", appointment, 0, 0, 0, Map.of("slotId", Long.toString(second))), "Appointment rescheduled.", "&view=appointments");
        assertEquals(0, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", first));
        assertEquals(1, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", second));
        // Bring the reserved window into its arrival period without relying on the wall-clock hour.
        try (var c = db.getConnection()) { Sql.update(c, "UPDATE appointment_slots SET starts_at=?,ends_at=? WHERE id=?", java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(300), second); }
        assertFeedback(browserCommand(visitor, user, "CHECK_IN", appointment, 0, 1, 0, Map.of()), "Checked in.", "&view=visits");
        long visit;
        try (var c = db.getConnection()) { visit = Sql.one(c, "SELECT visit_id FROM appointments WHERE id=?", r -> r.getLong(1), appointment).orElseThrow(); }
        assertFeedback(browserCommand(visitor, user, "CANCEL_VISIT", visit, 0, 0, 0, Map.of()), "Visit cancelled and removed", "&view=visits");
        assertEquals(1, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", second), "An arrived visit retains its consumed arrival quota.");
        assertFeedback(browserCommand(visitor, user, "BOOK", first, 0, 0, 0, Map.of()), "Appointment booked.", "&view=appointments");
        long future;
        try (var c = db.getConnection()) { future = Sql.one(c, "SELECT id FROM appointments WHERE user_id=? AND service_id=? AND state='BOOKED'", r -> r.getLong(1), fixture.user().id(), fixture.service()).orElseThrow(); }
        var noAlternative = get(visitor, "/app?serviceId=" + fixture.service() + "&view=appointments");
        assertEquals(200, noAlternative.statusCode(), noAlternative.body());
        assertTrue(noAlternative.body().contains("No alternative windows are currently available."));
        assertFalse(noAlternative.body().contains("name=\"operation\" value=\"RESCHEDULE\""), "An appointment without another available window must not offer an unusable move form");
        assertFeedback(browserCommand(visitor, user, "CANCEL_APPOINTMENT", future, 0, 0, 0, Map.of()), "Appointment cancelled.", "&view=appointments");
        assertEquals(0, count(db, "SELECT reserved FROM appointment_slots WHERE id=?", first));
    }
    @Test @Order(7) void browserCounterAndAdministrationFeedbackReturnsToTheRelevantSection() throws Exception {
        var operator = client(); var staff = signIn(operator, fixture.staff());
        assertFeedback(browserCommand(operator, staff, "CLAIM", 0, fixture.counter(), 0, 0, Map.of()), "You're now operating this counter.", "&view=counters");
        assertFeedback(browserCommand(operator, staff, "PAUSE", 0, fixture.counter(), 0, 1, Map.of("paused", "true")), "Counter paused.", "&view=counters");
        assertFeedback(browserCommand(operator, staff, "PAUSE", 0, fixture.counter(), 0, 1, Map.of("paused", "false")), "Counter resumed.", "&view=counters");
        assertFeedback(browserCommand(operator, staff, "RELEASE", 0, fixture.counter(), 0, 1, Map.of()), "Counter released.", "&view=counters");
        var administrator = client(); var admin = signIn(administrator, fixture.admin());
        assertFeedback(browserCommand(administrator, admin, "CREATE_COUNTER", 0, 0, 0, 0, Map.of("name", "Feedback counter")), "Counter created.", "&view=administration");
        var created = browserCommand(administrator, admin, "CREATE_SERVICE", 0, 0, 0, 0, Map.of("name", "Feedback service " + UUID.randomUUID(), "description", "Browser feedback verification"));
        assertEquals(200, created.statusCode(), created.body());
        String destination = JSON.readTree(created.body()).get("redirect").asText();
        assertTrue(destination.matches("/queueless/app\\?serviceId=[1-9][0-9]*&view=administration"), destination);
        assertNotEquals("/queueless/app?serviceId=" + fixture.service() + "&view=administration", destination, "A created service must become the selected service.");
        assertEquals(200, get(administrator, destination.substring("/queueless".length())).statusCode());
    }
    @Test @Order(8) void browserLoginAndLogoutShowOneTimeConfirmationAndRotateTheSession() throws Exception {
        var visitor = client();
        var page = get(visitor, "/login");
        var loggedIn = form(visitor, "/login", Map.of("csrf", csrf(page.body()), "email", email(fixture.user()), "password", password));
        assertEquals(200, loggedIn.statusCode(), loggedIn.body());
        assertTrue(loggedIn.body().contains("signed in. Choose a service"));
        assertFalse(get(visitor, "/app?serviceId=" + fixture.service()).body().contains("signed in. Choose a service"));
        var session = JSON.readValue(get(visitor, "/api/session").body(), Api.Session.class);
        var loggedOut = form(visitor, "/logout", Map.of("csrf", session.csrfToken()));
        assertEquals(200, loggedOut.statusCode(), loggedOut.body());
        assertTrue(loggedOut.body().contains("signed out. You can sign in again"));
        assertFalse(get(visitor, "/login").body().contains("signed out. You can sign in again"));
        var anonymous = JSON.readValue(get(visitor, "/api/session").body(), Api.Session.class);
        assertNull(anonymous.user()); assertNotEquals(session.csrfToken(), anonymous.csrfToken());
    }
    @Test @Order(9) void workspacePagesRenderOnlyTheirOwnContentAndPreserveTheSelectedService() throws Exception {
        var visitor = client(); var userSession = signIn(visitor, fixture.user());
        var administrator = client(); var adminSession = signIn(administrator, fixture.admin());
        var sections = List.of("overview", "visits", "appointments", "notifications", "counters", "reports", "administration");
        var expectedTitles = Map.of("overview", "Overview", "visits", "Queue &amp; visits", "appointments", "Appointments", "notifications", "Notifications",
                "counters", "Service counters", "reports", "Reports", "administration", "Administration");
        for (String selected : sections) {
            var client = Set.of("counters", "reports", "administration").contains(selected) ? administrator : visitor;
            var page = get(client, "/app?serviceId=" + fixture.service() + "&view=" + selected);
            assertEquals(200, page.statusCode(), selected + ": " + page.body());
            assertTrue(page.body().contains("<h1>" + expectedTitles.get(selected) + "</h1>"), selected + " must have its own page heading");
            for (String section : sections) {
                assertEquals(section.equals(selected), page.body().contains("id=\"" + section + "\""), selected + " must render only its own workspace content: " + section);
            }
            for (String destination : List.of("overview", "visits", "appointments", "notifications")) {
                assertTrue(page.body().contains("/app?serviceId=" + fixture.service() + "&amp;view=" + destination)
                        || page.body().contains("/app?serviceId=" + fixture.service() + "&view=" + destination), "Navigation must preserve service for " + destination);
                assertFalse(page.body().contains("href=\"#" + destination + "\""), "Workspace navigation must load a page rather than scroll");
            }
        }
        var defaultPage = get(visitor, "/app?serviceId=" + fixture.service());
        assertEquals(200, defaultPage.statusCode(), defaultPage.body());
        assertTrue(defaultPage.body().contains("id=\"overview\""));
        assertFalse(defaultPage.body().contains("id=\"notifications\""));
        assertFalse(defaultPage.body().contains("name=\"operation\" value=\"JOIN\""), "Overview is a summary; queue admission belongs on Queue & visits");
        assertFalse(defaultPage.body().contains("Working hours &amp; closures"), "Working hours belong beside admission and booking controls");
        var joined = result(json(visitor, "/api/commands", command("JOIN", fixture.service(), 0, 0, 0, 0, Map.of()), userSession.csrfToken()));
        result(json(administrator, "/api/commands", command("JOIN", fixture.service(), 0, 0, 0, 0, Map.of()), adminSession.csrfToken()));
        var snapshot = JSON.readValue(get(visitor, "/api/snapshot?serviceId=" + fixture.service()).body(), Api.Snapshot.class);
        String token = snapshot.visits().stream().filter(visit -> visit.id() == joined.entityId()).findFirst().orElseThrow().token();
        var overview = get(visitor, "/app?serviceId=" + fixture.service() + "&view=overview");
        assertEquals(1, summaryCount(overview.body(), "Your active visits"), "The visitor summary must count only their own unresolved visits");
        assertFalse(overview.body().contains(token), "Full tokens and visit actions belong on Queue & visits");
        var serviceOverview = get(administrator, "/app?serviceId=" + fixture.service() + "&view=overview");
        assertEquals(2, summaryCount(serviceOverview.body(), "Active service visits"), "Operator summaries must identify and count service-wide visits");
        var visits = get(visitor, "/app?serviceId=" + fixture.service() + "&view=visits");
        assertEquals(200, visits.statusCode(), visits.body());
        assertTrue(visits.body().contains("name=\"operation\" value=\"JOIN\""));
        assertTrue(visits.body().contains("Working hours &amp; closures"));
        assertTrue(visits.body().contains(token));
        assertTrue(visits.body().contains("Full visit history"));
        var selectedAppointments = get(visitor, "/app?serviceId=" + fixture.service() + "&view=appointments");
        var other = TestSupport.fixture(db);
        var choices = get(visitor, "/app?serviceId=" + fixture.service() + "&view=appointments");
        var picker = Pattern.compile("(?s)<form\\b[^>]*class=\"service-picker\"[^>]*>.*?</form>").matcher(choices.body());
        assertTrue(picker.find(), "Service selection must work as a normal GET form without JavaScript");
        String pickerForm = picker.group();
        assertTrue(pickerForm.contains("method=\"get\"") && pickerForm.contains("action=\"/queueless/app\""));
        assertTrue(pickerForm.contains("name=\"view\" value=\"appointments\""), "Changing service must retain the current workspace page");
        assertTrue(pickerForm.contains("name=\"serviceId\""));
        assertTrue(Pattern.compile("<option\\b[^>]*value=\"" + fixture.service() + "\"[^>]*\\bselected(?:\\s|=|>)").matcher(pickerForm).find(), "The selected service must remain visible");
        assertTrue(pickerForm.contains("value=\"" + other.service() + "\""), "The picker must offer other accessible services");
        var switched = get(visitor, "/app?serviceId=" + other.service() + "&view=appointments");
        assertEquals(200, switched.statusCode(), switched.body());
        assertTrue(switched.body().contains("<h1>Appointments</h1>"));
        assertTrue(switched.body().contains("/app?serviceId=" + other.service() + "&amp;view=visits"));
        assertFalse(get(visitor, "/app?serviceId=" + fixture.service() + "&view=notifications").body().contains("class=\"service-picker\""), "The personal inbox spans services and must not offer a service filter");
        assertTrue(selectedAppointments.body().contains("Full appointment history"));
    }
    @Test @Order(10) void directWorkspacePagesEnforceRolesServiceAccessAndTheViewAllowlist() throws Exception {
        var visitor = client(); signIn(visitor, fixture.user());
        for (String restricted : List.of("counters", "reports", "administration")) {
            assertEquals(403, get(visitor, "/app?serviceId=" + fixture.service() + "&view=" + restricted).statusCode(), restricted);
        }
        var operator = client(); signIn(operator, fixture.staff());
        assertEquals(200, get(operator, "/app?serviceId=" + fixture.service() + "&view=counters").statusCode());
        assertEquals(200, get(operator, "/app?serviceId=" + fixture.service() + "&view=reports").statusCode());
        assertEquals(403, get(operator, "/app?serviceId=" + fixture.service() + "&view=administration").statusCode());
        var other = TestSupport.fixture(db);
        assertEquals(403, get(operator, "/app?serviceId=" + other.service() + "&view=counters").statusCode());
        for (String invalid : List.of("unknown", "", "../admin", "APPOINTMENTS")) {
            assertEquals(404, get(visitor, "/app?serviceId=" + fixture.service() + "&view=" + invalid).statusCode(), invalid);
        }
        var anonymous = client();
        var page = get(anonymous, "/app?serviceId=" + fixture.service() + "&view=notifications");
        assertEquals(200, page.statusCode(), page.body());
        assertEquals("/queueless/login", page.uri().getPath());
        assertFalse(page.body().contains("id=\"notifications\""));
    }
    @Test @Order(11) void notificationInboxUsesReadableMessagesAndBulkReadEnforcesBrowserSecurity() throws Exception {
        var visitor = client(); var user = signIn(visitor, fixture.user());
        long joined = notification(fixture.user().id(), "Visit #31001: join", false);
        long cancelled = notification(fixture.user().id(), "Visit #31001: cancel_visit", false);
        notification(fixture.user().id(), "<script>not a message</script>", true);
        long foreign = notification(fixture.staff().id(), "An update for another account", false);
        var page = get(visitor, "/app?serviceId=" + fixture.service() + "&view=notifications");
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("All services"), "The inbox scope must be explicit");
        assertFalse(page.body().contains("cancel_visit"), "Legacy event codes must not reach visitors");
        assertFalse(page.body().contains("Visit #31001: join"));
        assertTrue(page.body().contains("Visit #31001 joined the queue."));
        assertTrue(page.body().contains("Visit #31001 was cancelled."));
        assertTrue(page.body().contains("&lt;script&gt;not a message&lt;/script&gt;"));
        assertFalse(page.body().contains("<script>not a message</script>"));
        assertTrue(page.body().contains("Mark all as read"));
        assertTrue(Pattern.compile("(?s)class=\"nav-badge\"[^>]*>.*?Unread:.*?</span>\\s*2\\s*</span>").matcher(page.body()).find(), "The sidebar badge must expose the actual unread count to screen readers");
        assertFalse(page.body().contains("<h2>Your notifications</h2>"), "The card must not repeat the page heading");
        assertFalse(page.body().contains("class=\"service-picker\""));
        var bulkForm = commandFields(page.body(), "READ_NOTIFICATIONS");
        assertEquals(Set.of(Long.toString(joined), Long.toString(cancelled)), new HashSet<>(List.of(bulkForm.get("notificationIds").split(","))), "Only the unread IDs actually shown may be submitted");
        var anotherService = TestSupport.fixture(db);
        var anotherServiceInbox = get(visitor, "/app?serviceId=" + anotherService.service() + "&view=notifications");
        assertEquals(200, anotherServiceInbox.statusCode(), anotherServiceInbox.body());
        assertEquals(bulkForm.get("notificationIds"), commandFields(anotherServiceInbox.body(), "READ_NOTIFICATIONS").get("notificationIds"), "Changing the selected service must not filter a personal inbox");
        var before = JSON.readValue(get(visitor, "/api/snapshot?serviceId=" + fixture.service()).body(), Api.Snapshot.class);
        assertEquals(Api.NotificationKind.QUEUED, before.notifications().stream().filter(n -> n.id() == joined).findFirst().orElseThrow().kind());
        assertEquals(Api.NotificationKind.CANCELLED, before.notifications().stream().filter(n -> n.id() == cancelled).findFirst().orElseThrow().kind(), "Opposite events must have distinct semantic cues for both clients");
        var badCsrf = new LinkedHashMap<>(bulkForm); badCsrf.put("csrf", "invalid");
        assertProblem(form(visitor, "/command", badCsrf, true), 403, "CSRF");
        var mixedOwnership = new LinkedHashMap<>(bulkForm); mixedOwnership.put("notificationIds", bulkForm.get("notificationIds") + "," + foreign);
        assertProblem(form(visitor, "/command", mixedOwnership, true), 409, "CONFLICT");
        assertEquals(0, count(db, "SELECT COUNT(*) FROM notifications WHERE id IN (?,?) AND is_read=TRUE", joined, cancelled), "Rejected requests must leave all selected notifications unread");
        var anonymousClient = client();
        var anonymousSession = JSON.readValue(get(anonymousClient, "/api/session").body(), Api.Session.class);
        var anonymousForm = new LinkedHashMap<>(bulkForm); anonymousForm.put("csrf", anonymousSession.csrfToken());
        assertProblem(form(anonymousClient, "/command", anonymousForm, true), 401, "AUTHENTICATION");
        assertFeedback(form(visitor, "/command", bulkForm, true), "2 notifications marked as read.", "&view=notifications");
        assertEquals(2, count(db, "SELECT COUNT(*) FROM notifications WHERE id IN (?,?) AND is_read=TRUE", joined, cancelled));
        assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", foreign));
        var updated = get(visitor, "/app?serviceId=" + fixture.service() + "&view=notifications");
        assertEquals(200, updated.statusCode(), updated.body());
        assertFalse(updated.body().contains("name=\"operation\" value=\"READ_NOTIFICATIONS\""), "A fully read inbox must not offer a redundant bulk action");
        assertFalse(updated.body().contains("class=\"nav-badge\""), "The unread badge must disappear when the count reaches zero");
        var snapshot = JSON.readValue(get(visitor, "/api/snapshot?serviceId=" + fixture.service()).body(), Api.Snapshot.class);
        assertTrue(snapshot.notifications().stream().allMatch(Api.Notification::read));
        assertTrue(snapshot.notifications().stream().noneMatch(n -> n.message().contains("cancel_visit")), "Desktop and browser must receive the same readable messages");
    }
    @Test @Order(12) void visitPresentationDistinguishesTokensFromReferencesAndTerminalOutcomes() throws Exception {
        var visitor = client(); var user = signIn(visitor, fixture.user());
        var emptyOverview = get(visitor, "/app?serviceId=" + fixture.service() + "&view=overview");
        assertEquals(200, emptyOverview.statusCode(), emptyOverview.body());
        assertEquals(0, summaryCount(emptyOverview.body(), "Your active visits"));
        assertEquals(0, summaryCount(emptyOverview.body(), "Your active appointments"));
        assertTrue(emptyOverview.body().contains("Check working hours and walk-in availability before joining."));
        assertTrue(emptyOverview.body().contains("Browse arrival windows to plan your next visit."));
        assertTrue(emptyOverview.body().contains("Your inbox keeps earlier updates for reference."));
        assertFalse(emptyOverview.body().contains("name=\"operation\" value=\"JOIN\""), "Guidance must not duplicate the queue-admission form");
        var appointments = get(visitor, "/app?serviceId=" + fixture.service() + "&view=appointments");
        assertEquals(200, appointments.statusCode(), appointments.body());
        assertFalse(appointments.body().contains("<h2>Appointments</h2>"));
        assertFalse(appointments.body().contains("<th>Appointment</th>"), "An empty appointment list should offer guidance instead of empty column headings");
        assertTrue(appointments.body().contains("No appointment windows are available to book right now."));
        assertTrue(appointments.body().contains("Check walk-in availability"), "Unavailable appointments should point visitors to the walk-in page without promising admission");
        assertTrue(appointments.body().contains("You have no bookings listed for this service."));
        assertTrue(appointments.body().contains("Review arrival windows"));
        var joined = result(json(visitor, "/api/commands", command("JOIN", fixture.service(), 0, 0, 0, 0, Map.of()), user.csrfToken()));
        var snapshot = JSON.readValue(get(visitor, "/api/snapshot?serviceId=" + fixture.service()).body(), Api.Snapshot.class);
        String token = snapshot.visits().stream().filter(v -> v.id() == joined.entityId()).findFirst().orElseThrow().token();
        int number = Integer.parseInt(token.substring(token.lastIndexOf('-') + 1));
        var visits = get(visitor, "/app?serviceId=" + fixture.service() + "&view=visits");
        assertEquals(200, visits.statusCode(), visits.body());
        assertTrue(visits.body().contains("Token #" + number), "The daily token must be the prominent label");
        assertTrue(visits.body().contains(token), "The service/date-qualified code must remain visible");
        assertTrue(visits.body().contains("Visit reference #" + joined.entityId()), "The record reference must not be presented as the queue number");
        assertFeedback(browserCommand(visitor, user, "CANCEL_VISIT", joined.entityId(), 0, joined.version(), 0, Map.of()), "Visit cancelled and removed", "&view=visits");
        var cancelledPage = get(visitor, "/app?serviceId=" + fixture.service() + "&view=visits");
        assertEquals(200, cancelledPage.statusCode(), cancelledPage.body());
        assertTrue(cancelledPage.body().contains("No further action"));
        assertFalse(cancelledPage.body().contains("name=\"operation\" value=\"REQUEUE\""), "A cancelled visit is terminal; rejoining is a new admission");
        var history = get(visitor, "/history?serviceId=" + fixture.service());
        assertEquals(200, history.statusCode(), history.body());
        assertTrue(history.body().contains("Token #" + number));
        assertTrue(history.body().contains("Visit reference #" + joined.entityId()), "History must retain the same token/reference distinction as the current list");
    }
    @Test @Order(13) void largeInboxUsesBoundedNativeFormsAndLeavesUnseenArrivalsUnread() throws Exception {
        var visitor = client(); signIn(visitor, fixture.user());
        var unread = new LinkedHashSet<String>();
        for (int i = 0; i < 101; i++) unread.add(Long.toString(notification(fixture.user().id(), "Inbox update " + i, false)));
        var page = get(visitor, "/app?serviceId=" + fixture.service() + "&view=notifications");
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("Mark 100 as read"), "The bulk label must describe the bounded batch honestly");
        assertFalse(page.body().contains("Mark all as read"));
        var batch = commandFields(page.body(), "READ_NOTIFICATIONS");
        var submittedIds = new HashSet<>(List.of(batch.get("notificationIds").split(",")));
        assertEquals(100, submittedIds.size());
        assertTrue(unread.containsAll(submittedIds));
        var displayedUnreadIds = new HashSet<String>();
        var forms = Pattern.compile("(?s)<form\\b[^>]*>.*?</form>").matcher(page.body());
        while (forms.find()) {
            String candidate = forms.group();
            if (candidate.contains("name=\"operation\" value=\"READ_NOTIFICATION\"")) displayedUnreadIds.add(commandFields(candidate, "READ_NOTIFICATION").get("entityId"));
        }
        assertEquals(unread, displayedUnreadIds, "Bounding the action must not silently omit unread messages from the inbox");
        assertTrue(displayedUnreadIds.containsAll(submittedIds), "A bulk form may only act on IDs in the rendered snapshot");
        long arrivedLater = notification(fixture.user().id(), "Arrived after this page was rendered", false);
        var nativeSubmission = form(visitor, "/command", batch);
        assertEquals(200, nativeSubmission.statusCode(), nativeSubmission.body());
        assertEquals("/queueless/app", nativeSubmission.uri().getPath());
        assertEquals("serviceId=" + fixture.service() + "&view=notifications", nativeSubmission.uri().getQuery());
        assertTrue(nativeSubmission.body().contains("100 notifications marked as read."), "The standard HTML form must provide feedback without JavaScript");
        assertEquals(100, count(db, "SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=TRUE", fixture.user().id()));
        assertEquals(2, count(db, "SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=FALSE", fixture.user().id()));
        assertEquals(0, count(db, "SELECT is_read FROM notifications WHERE id=?", arrivedLater), "An unseen arrival must not be included in an older page's action");
        var remaining = commandFields(nativeSubmission.body(), "READ_NOTIFICATIONS");
        var expectedRemaining = new HashSet<>(unread); expectedRemaining.removeAll(submittedIds); expectedRemaining.add(Long.toString(arrivedLater));
        assertEquals(expectedRemaining, new HashSet<>(List.of(remaining.get("notificationIds").split(","))));
        assertTrue(nativeSubmission.body().contains("Mark all as read"), "The next rendered form should offer the two remaining unread messages");
    }
    @Test @Order(14) void authenticationLimiterBoundsRequestsWithoutForwardedAddressTrust() throws Exception {
        var client = client();
        HttpResponse<String> response = null;
        for (int i = 0; i < 61; i++) {
            response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/session")).header("Content-Type", "application/json").header("X-Forwarded-For", "192.0.2." + i).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) break;
            assertEquals(403, response.statusCode());
        }
        assertNotNull(response); assertEquals(429, response.statusCode(), response.body());
        assertEquals("300", response.headers().firstValue("Retry-After").orElse(""));
        assertEquals("RATE_LIMIT", JSON.readTree(response.body()).get("code").asText());
    }
    private static HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build(); }
    private static long notification(long user, String message, boolean read) throws Exception {
        try (var c = db.getConnection()) {
            long work = Sql.insert(c, "INSERT INTO notification_work(user_id,event_key,message,done) VALUES(?,?,?,TRUE)", user, "web-ux:" + UUID.randomUUID(), message);
            return Sql.insert(c, "INSERT INTO notifications(user_id,work_id,message,is_read) VALUES(?,?,?,?)", user, work, message, read);
        }
    }
    private static Map<String, String> commandFields(String page, String operation) {
        var forms = Pattern.compile("(?s)<form\\b[^>]*>.*?</form>").matcher(page);
        while (forms.find()) {
            String candidate = forms.group();
            if (!candidate.contains("name=\"operation\" value=\"" + operation + "\"")) continue;
            var values = new LinkedHashMap<String, String>();
            var inputs = Pattern.compile("<input\\b[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"").matcher(candidate);
            while (inputs.find()) values.put(inputs.group(1), inputs.group(2));
            assertTrue(values.containsKey("csrf") && values.containsKey("requestId"), "Every rendered command must preserve CSRF and retry identity");
            return values;
        }
        fail("The page must render a command form for " + operation);
        return Map.of();
    }
    private static HttpResponse<String> get(HttpClient client, String path) throws Exception { return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private static HttpResponse<String> form(HttpClient client, String path, Map<String, String> form) throws Exception {
        return form(client, path, form, false);
    }
    private static HttpResponse<String> form(HttpClient client, String path, Map<String, String> form, boolean acceptJson) throws Exception {
        String body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded");
        if (acceptJson) request.header("Accept", "application/json");
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> browserCommand(HttpClient client, Api.Session session, String operation, long entityId, long counterId, long expectedVersion, long claimVersion, Map<String, String> fields) throws Exception {
        var values = new LinkedHashMap<>(fields);
        values.put("csrf", session.csrfToken()); values.put("requestId", UUID.randomUUID().toString()); values.put("operation", operation);
        values.put("serviceId", Long.toString(fixture.service())); values.put("entityId", Long.toString(entityId)); values.put("counterId", Long.toString(counterId));
        values.put("expectedVersion", Long.toString(expectedVersion)); values.put("claimVersion", Long.toString(claimVersion));
        return form(client, "/command", values, true);
    }
    private void assertFeedback(HttpResponse<String> response, String message, String section) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var feedback = JSON.readTree(response.body());
        assertTrue(feedback.get("message").asText().contains(message), response.body());
        assertFalse(feedback.get("replayed").asBoolean());
        assertEquals("/queueless/app?serviceId=" + fixture.service() + section, feedback.get("redirect").asText());
    }
    private static void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        var problem = JSON.readValue(response.body(), Api.Problem.class);
        assertEquals(code, problem.code()); assertFalse(problem.retryable()); assertFalse(problem.message().isBlank());
    }
    private static HttpResponse<String> json(HttpClient client, String path, Object value, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(value))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private Api.Session signIn(HttpClient client, Api.User user) throws Exception {
        var anonymous = JSON.readValue(get(client, "/api/session").body(), Api.Session.class);
        var response = json(client, "/api/session", new Api.Login(email(user), password), anonymous.csrfToken());
        assertEquals(200, response.statusCode(), response.body()); return JSON.readValue(response.body(), Api.Session.class);
    }
    private static String email(Api.User user) throws Exception { try (var c = db.getConnection()) { return Sql.one(c, "SELECT email FROM users WHERE id=?", r -> r.getString(1), user.id()).orElseThrow(); } }
    private static Api.Result result(HttpResponse<String> response) throws Exception { assertEquals(200, response.statusCode(), response.body()); return JSON.readValue(response.body(), Api.Result.class); }
    private static int summaryCount(String page, String label) {
        var match = Pattern.compile("<strong>(\\d+)</strong>\\s*<span>" + Pattern.quote(label) + "</span>").matcher(page);
        assertTrue(match.find(), "A summary count must have the correct scope label: " + label);
        return Integer.parseInt(match.group(1));
    }
    private static String csrf(String page) { var match = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(page); assertTrue(match.find(), "A CSRF input must be rendered"); return match.group(1); }
}
