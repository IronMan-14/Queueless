package io.queueless.desktop;

/** A safe server-provided business failure, not an internal stack trace. */
public final class ApiException extends Exception {
    private final int status;
    private final String code;
    private final boolean retryable;

    public ApiException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public int status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
