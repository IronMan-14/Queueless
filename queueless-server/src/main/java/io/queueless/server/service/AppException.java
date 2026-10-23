package io.queueless.server.service;

/** An expected, safe-to-display application error. Unexpected errors never expose SQL or secrets. */
public class AppException extends RuntimeException {
    private final int status;
    private final String code;
    public AppException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static AppException conflict(String message) { return new AppException(409, "CONFLICT", message); }
    public static void require(boolean condition, String message) { if (!condition) throw conflict(message); }
}
