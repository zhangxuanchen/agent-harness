package io.etclovg.codepilot.tools;

/**
 * API 调用异常。
 * <p>对应书中 Ch05 §5.2 —— 工具调用外部 API 时的异常统一表示。
 * <p>封装 HTTP 状态码、错误响应体与是否可重试标志，供
 * {@code StructuredErrorHandler} 与重试中间件据此决策。
 */
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final int statusCode;
    private final boolean retryable;

    /**
     * 构造 API 异常。
     *
     * @param message   错误信息
     * @param errorCode 错误代码
     * @param statusCode HTTP 状态码
     * @param retryable 是否可重试
     */
    public ApiException(String message, String errorCode, int statusCode, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    /**
     * 构造带原因的 API 异常。
     *
     * @param message   错误信息
     * @param errorCode 错误代码
     * @param statusCode HTTP 状态码
     * @param retryable 是否可重试
     * @param cause     原始异常
     */
    public ApiException(String message, String errorCode, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 是否可重试。
     * <p>通常 5xx 与 429 视为可重试，4xx（除 429）视为不可重试。
     *
     * @return 可重试返回 true
     */
    public boolean isRetryable() {
        return retryable;
    }
}
