package io.queueless.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PreferencesStoreTest {
    @TempDir Path directory;
    private final PreferencesStore store = new PreferencesStore();

    @Test void roundTripPreservesOnlyValidatedPreferencesAndReplacesAtomically() throws Exception {
        Path path = directory.resolve("nested/preferences.bin");
        store.save(path, new OperatorPreferences(12, 43, 8, 1200, 800));
        store.save(path, new OperatorPreferences(15, 44, 10, 1300, 850));
        OperatorPreferences result = store.load(path);
        assertEquals(15, result.serviceId());
        assertEquals(44, result.counterId());
        assertEquals(10, result.refreshSeconds());
        assertEquals(1300, result.width());
        try (var files = Files.list(path.getParent())) { assertEquals(1, files.count()); }
    }

    @Test void missingCorruptAndOversizedFilesFallBackWithoutCrashing() throws Exception {
        Path path = directory.resolve("preferences.bin");
        assertDefaults(store.load(path));
        Files.writeString(path, "not an object stream");
        assertDefaults(store.load(path));
        Files.write(path, new byte[20_000]);
        assertDefaults(store.load(path));
    }

    @Test void rejectsUnrelatedClassBeforeItsDeserializationCodeRuns() throws Exception {
        Path path = directory.resolve("hostile.bin");
        UntrustedPreferences.executed = false;
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(path))) { output.writeObject(new UntrustedPreferences()); }
        assertDefaults(store.load(path));
        assertFalse(UntrustedPreferences.executed);
    }

    @Test void validatesRestoredFieldsInsteadOfTrustingSerializedConstructorBypass() throws Exception {
        OperatorPreferences invalid = OperatorPreferences.defaults();
        var field = OperatorPreferences.class.getDeclaredField("refreshSeconds");
        field.setAccessible(true);
        field.setInt(invalid, 0);
        Path path = directory.resolve("invalid.bin");
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(path))) { output.writeObject(invalid); }
        assertDefaults(store.load(path));
        assertThrows(IllegalArgumentException.class, () -> new OperatorPreferences(-1, 1, 5, 1200, 800));
        assertThrows(IllegalArgumentException.class, () -> new OperatorPreferences(1, 1, 5, Double.NaN, 800));
    }

    private void assertDefaults(OperatorPreferences result) {
        assertEquals(0, result.serviceId());
        assertEquals(0, result.counterId());
        assertEquals(5, result.refreshSeconds());
    }

    private static final class UntrustedPreferences implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        static boolean executed;
        @Serial private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
            executed = true;
            input.defaultReadObject();
        }
    }
}
