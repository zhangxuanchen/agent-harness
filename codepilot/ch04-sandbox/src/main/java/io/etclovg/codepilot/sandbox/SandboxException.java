package io.etclovg.codepilot.sandbox;

/**
 * 沙箱异常。
 * <p>对应书中 Ch04 §4.2 —— 沙箱操作相关的异常类型。
 */
public class SandboxException extends RuntimeException {

    private final String sandboxId;
    private final ErrorType errorType;

    /**
     * 错误类型枚举。
     */
    public enum ErrorType {
        CONTAINER_CREATE_FAILED,
        CONTAINER_START_FAILED,
        CONTAINER_TIMEOUT,
        NETWORK_DENIED,
        FILESYSTEM_ERROR,
        RESOURCE_LIMIT_EXCEEDED
    }

    public SandboxException(String sandboxId, ErrorType errorType, String message) {
        super(message);
        this.sandboxId = sandboxId;
        this.errorType = errorType;
    }

    public SandboxException(String sandboxId, ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.sandboxId = sandboxId;
        this.errorType = errorType;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}