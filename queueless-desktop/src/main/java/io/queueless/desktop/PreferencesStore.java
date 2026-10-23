package io.queueless.desktop;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Narrow native serialization with bounds, validation and safe fallback. */
public final class PreferencesStore {
    private static final long MAX_BYTES = 16_384;

    public OperatorPreferences load(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return OperatorPreferences.defaults();
            try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(path))) {
                input.setObjectInputFilter(info -> {
                    if (info.depth() > 4 || info.references() > 32 || info.streamBytes() > MAX_BYTES || info.arrayLength() > 32) {
                        return ObjectInputFilter.Status.REJECTED;
                    }
                    Class<?> type = info.serialClass();
                    if (type == null) return ObjectInputFilter.Status.UNDECIDED;
                    return type == OperatorPreferences.class ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
                });
                Object value = input.readObject();
                if (!(value instanceof OperatorPreferences preferences)) return OperatorPreferences.defaults();
                preferences.validate();
                return preferences;
            }
        } catch (IOException | ClassNotFoundException | IllegalArgumentException failure) {
            return OperatorPreferences.defaults();
        }
    }

    public void save(Path path, OperatorPreferences preferences) throws IOException {
        preferences.validate();
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), "preferences-", ".tmp");
        try {
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
                output.writeObject(preferences);
            }
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
