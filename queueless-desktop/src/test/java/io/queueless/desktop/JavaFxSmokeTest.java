package io.queueless.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.queueless.contracts.Api;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in native UI test against the runner's isolated Servlet/MySQL application. */
@EnabledIfEnvironmentVariable(named = "QUEUELESS_FX_TEST", matches = "true")
class JavaFxSmokeTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private String address;
    private Stage stage;
    private QueueLessApp application;

    @Test void realControlsLogInClaimCallStartCompleteAndRenderScreenshots() throws Exception {
        // Verify isolation before signing in or creating any persistent fixture.
        address = verifyIsolatedBackend(System.getenv());
        String adminEmail = required("QUEUELESS_FX_ADMIN_EMAIL");
        String adminPassword = required("QUEUELESS_FX_ADMIN_PASSWORD");
        HttpApiClient administrator = new HttpApiClient(address);
        administrator.login(adminEmail, adminPassword);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long service = administrator.command(command("CREATE_SERVICE", 0, 0, 0, 0, 0,
                Map.of("name", "FX smoke " + suffix, "description", "Isolated desktop integration fixture"))).entityId();
        administrator.command(command("HOURS", service, 0, 0, 0, 0,
                Map.of("day", Integer.toString(LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek().getValue()), "opens", "00:00", "closes", "23:59:59")));
        long counter = administrator.command(command("CREATE_COUNTER", service, 0, 0, 0, 0, Map.of("name", "Desktop test counter"))).entityId();
        String staffEmail = "fx-staff-" + suffix + "@example.test";
        String fixturePassword = "Fx-test-" + UUID.randomUUID() + "!";
        Api.Session staff = register(staffEmail, fixturePassword);
        administrator.command(command("ASSIGN_STAFF", service, 0, 0, 0, 0, Map.of("userId", Long.toString(staff.user().id()))));
        String visitorEmail = "fx-visitor-" + suffix + "@example.test";
        register(visitorEmail, fixturePassword);
        HttpApiClient visitor = new HttpApiClient(address);
        visitor.login(visitorEmail, fixturePassword);
        long visit = visitor.command(command("JOIN", service, 0, 0, 0, 0, Map.of())).entityId();

        Path preferencesFile = temporary.resolve("operator-preferences.bin");
        // An inaccessible old selection must not prevent the new account from loading its assigned service.
        new PreferencesStore().save(preferencesFile, new OperatorPreferences(Long.MAX_VALUE, 0, 3, 1180, 780));
        System.setProperty("queueless.preferences", preferencesFile.toString());
        System.setProperty("queueless.backend", address);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(15, TimeUnit.SECONDS));
        try {
            onFx(() -> {
                application = new QueueLessApp();
                stage = new Stage();
                application.start(stage);
                assertTrue(stage.isShowing());
                assertFalse(stage.getScene().getStylesheets().isEmpty());
                screenshot("desktop-login.png");
                ((TextField) node("login-email")).setText(staffEmail);
                ((PasswordField) node("login-password")).setText(fixturePassword);
                ((Button) node("sign-in")).fire();
                assertEquals("", ((PasswordField) node("login-password")).getText());
                return null;
            });
            awaitUi(() -> node("command-claim") instanceof Button button && !button.isDisabled()
                    && ((ComboBox<?>) node("service-selector")).getValue() != null, "staff dashboard with assigned counter");
            onFx(() -> {
                @SuppressWarnings("unchecked") var selector = (ComboBox<Api.Service>) node("service-selector");
                assertEquals(service, selector.getValue().id());
                screenshot("desktop-waiting.png");
                ((Button) node("command-claim")).fire();
                return null;
            });
            awaitUi(() -> !((Button) node("command-next")).isDisabled(), "counter claim");
            onFx(() -> { ((Button) node("command-next")).fire(); return null; });
            awaitUi(() -> !((Button) node("command-start")).isDisabled(), "called token");
            onFx(() -> { screenshot("desktop-called.png"); ((Button) node("command-start")).fire(); return null; });
            awaitUi(() -> !((Button) node("command-complete")).isDisabled(), "service started");
            onFx(() -> { ((Button) node("command-complete")).fire(); return null; });
            awaitUi(() -> !((Button) node("command-next")).isDisabled()
                    && ((Label) node("operation-status")).getText().toLowerCase().contains("complet"), "service completed");
            Api.Visit completed = visitor.snapshot(service).visits().stream().filter(item -> item.id() == visit).findFirst().orElseThrow();
            assertEquals("COMPLETED", completed.state(), "UI actions must change the real backend records.");
            assertEquals(3, completed.version());
            assertEquals("COMPLETED", visitor.visitHistory(service, 0, 50).items().getFirst().state());
            onFx(() -> { screenshot("desktop-completed.png"); ((Button) node("command-release")).fire(); return null; });
            awaitUi(() -> ((ComboBox<?>) node("counter-selector")).getValue() instanceof Api.Counter item && item.ownerId() == null, "counter release");
            onFx(() -> { ((Button) node("sign-out")).fire(); return null; });
            awaitUi(() -> node("login-feedback") instanceof Label label && label.getText().equals("Signed out."), "logout");
            assertEquals(service, new PreferencesStore().load(preferencesFile).serviceId());
            assertEquals(counter, new PreferencesStore().load(preferencesFile).counterId());
        } finally {
            onFx(() -> {
                if (application != null) application.stop();
                if (stage != null) stage.close();
                return null;
            });
            Platform.exit();
            System.clearProperty("queueless.preferences");
            System.clearProperty("queueless.backend");
            visitor.logout();
            administrator.logout();
        }
    }

    static String verifyIsolatedBackend(Map<String, String> environment) throws Exception {
        String address = environment.get("QUEUELESS_FX_BASE_URL");
        String proof = environment.get("QUEUELESS_FX_ISOLATION_PROOF");
        if (!"http://127.0.0.1:18081/queueless".equals(address)
                || proof == null || !proof.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Run scripts/verify-desktop-ui.ps1: the GUI test requires its isolated backend, not the demo application.");
        }
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
                HttpRequest.newBuilder(URI.create(address + "/__fx_isolation.txt"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !proof.equals(response.body().strip())) {
            throw new IllegalStateException("Isolated GUI test backend proof is missing or does not match this run; no fixtures were created.");
        }
        return address;
    }

    private Api.Session register(String email, String password) throws Exception {
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
        var initial = http.send(HttpRequest.newBuilder(URI.create(address + "/api/session")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, initial.statusCode());
        Api.Session anonymous = json.readValue(initial.body(), Api.Session.class);
        var response = http.send(HttpRequest.newBuilder(URI.create(address + "/api/register"))
                .header("Content-Type", "application/json").header("X-CSRF-Token", anonymous.csrfToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(new Api.Registration("FX integration fixture", email, password))))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, "Fixture registration failed: HTTP " + response.statusCode());
        return json.readValue(response.body(), Api.Session.class);
    }

    private Api.Command command(String operation, long service, long entity, long counter, long expected, long claim, Map<String, String> fields) {
        return new Api.Command(UUID.randomUUID().toString(), operation, service, entity, counter, expected, claim, fields);
    }

    private Node node(String id) { return stage.getScene().lookup("#" + id); }

    private void awaitUi(Callable<Boolean> ready, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (onFx(ready)) return;
            Thread.sleep(50);
        }
        String status = onFx(() -> {
            Node value = node("operation-status");
            if (value == null) value = node("login-feedback");
            return value instanceof Label label ? label.getText() : "no status";
        });
        fail("Timed out waiting for " + description + ": " + status);
    }

    private <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        Platform.runLater(work);
        return work.get(15, TimeUnit.SECONDS);
    }

    private void screenshot(String name) throws Exception {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        WritableImage snapshot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        }
        Path destination = Path.of("target", "fx-smoke", name);
        Files.createDirectories(destination.getParent());
        assertTrue(ImageIO.write(image, "png", destination.toFile()));
    }

    private String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " must be supplied for the opt-in integration test.");
        assertFalse(value.isBlank(), name + " must not be blank.");
        return value;
    }
}
