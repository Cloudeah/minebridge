package dev.minebridge.core;

public final class BridgeException extends RuntimeException {
    public final String code;
    public final boolean retryable;
    public BridgeException(String code, String message) { this(code, message, false); }
    public BridgeException(String code, String message, boolean retryable) {
        super(message); this.code = code; this.retryable = retryable;
    }
}
