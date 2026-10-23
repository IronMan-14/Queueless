package io.queueless.desktop;

import java.io.Serial;
import java.io.Serializable;

/** UI hints only. Server authorization never depends on this local file. */
public final class OperatorPreferences implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private final int formatVersion;
    private final long serviceId;
    private final long counterId;
    private final int refreshSeconds;
    private final double width;
    private final double height;

    public OperatorPreferences(long serviceId, long counterId, int refreshSeconds, double width, double height) {
        formatVersion = 1;
        this.serviceId = serviceId;
        this.counterId = counterId;
        this.refreshSeconds = refreshSeconds;
        this.width = width;
        this.height = height;
        validate();
    }

    public static OperatorPreferences defaults() { return new OperatorPreferences(0, 0, 5, 1180, 780); }

    void validate() {
        if (formatVersion != 1 || serviceId < 0 || counterId < 0 || refreshSeconds < 3 || refreshSeconds > 60
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width < 900 || width > 4000 || height < 650 || height > 2400) {
            throw new IllegalArgumentException("Unsupported or invalid operator preferences.");
        }
    }

    public long serviceId() { return serviceId; }
    public long counterId() { return counterId; }
    public int refreshSeconds() { return refreshSeconds; }
    public double width() { return width; }
    public double height() { return height; }
}
