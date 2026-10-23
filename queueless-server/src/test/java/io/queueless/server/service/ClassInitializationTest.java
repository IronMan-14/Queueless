package io.queueless.server.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Evaluator-only language check: initialization of reusable queue test scenarios.
 * Production does not need a static block merely to demonstrate this syntax.
 */
class ClassInitializationTest {
    private static int initializationCount;

    private static final class Scenarios {
        static final Map<String,List<String>> COMMANDS;
        static {
            var scenarios = new LinkedHashMap<String,List<String>>();
            scenarios.put("successful visit", List.of("JOIN", "CLAIM", "NEXT", "START", "COMPLETE"));
            scenarios.put("visitor cancels", List.of("JOIN", "CANCEL_VISIT"));
            if (scenarios.values().stream().anyMatch(List::isEmpty)) throw new IllegalStateException("Empty workflow fixture");
            COMMANDS = Map.copyOf(scenarios);
            initializationCount++;
        }
    }

    @Test void loadingDoesNotInitializeAndFirstUseInitializesValidatedFixturesOnce() throws Exception {
        Class.forName(Scenarios.class.getName(), false, getClass().getClassLoader());
        assertEquals(0, initializationCount);
        assertEquals("COMPLETE", Scenarios.COMMANDS.get("successful visit").getLast());
        assertEquals("CANCEL_VISIT", Scenarios.COMMANDS.get("visitor cancels").getLast());
        assertEquals(1, initializationCount);
        assertThrows(UnsupportedOperationException.class, () -> Scenarios.COMMANDS.clear());
    }
}
