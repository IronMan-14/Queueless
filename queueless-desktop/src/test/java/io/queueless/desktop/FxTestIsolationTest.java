package io.queueless.desktop;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FxTestIsolationTest {
    @Test void demoAndRemoteAddressesCannotReceiveFixtures() {
        for (String address : new String[]{"http://127.0.0.1:8080/queueless", "http://localhost:8080/queueless", "https://example.com/queueless"}) {
            assertThrows(IllegalStateException.class, () -> JavaFxSmokeTest.verifyIsolatedBackend(Map.of(
                    "QUEUELESS_FX_BASE_URL", address, "QUEUELESS_FX_ISOLATION_PROOF", "a".repeat(64))));
        }
    }

    @Test void missingOrInvalidProofCannotReceiveFixtures() {
        assertThrows(IllegalStateException.class, () -> JavaFxSmokeTest.verifyIsolatedBackend(Map.of()));
        assertThrows(IllegalStateException.class, () -> JavaFxSmokeTest.verifyIsolatedBackend(Map.of(
                "QUEUELESS_FX_BASE_URL", "http://127.0.0.1:18081/queueless")));
        assertThrows(IllegalStateException.class, () -> JavaFxSmokeTest.verifyIsolatedBackend(Map.of(
                "QUEUELESS_FX_BASE_URL", "http://127.0.0.1:18081/queueless", "QUEUELESS_FX_ISOLATION_PROOF", "invalid")));
    }
}
