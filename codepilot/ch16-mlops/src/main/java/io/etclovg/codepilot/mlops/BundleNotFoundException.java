package io.etclovg.codepilot.mlops;

/**
 * Bundle 未找到异常：当请求的 Agent 版本包不存在时抛出
 * 对应书中 Ch16 §16.2 —— Agent 部署与版本管理
 */
public class BundleNotFoundException extends RuntimeException {

    private final String bundleId;

    public BundleNotFoundException(String bundleId) {
        super("Agent Bundle 未找到: " + bundleId);
        this.bundleId = bundleId;
    }

    public BundleNotFoundException(String bundleId, String message) {
        super(message);
        this.bundleId = bundleId;
    }

    public BundleNotFoundException(String bundleId, Throwable cause) {
        super("Agent Bundle 未找到: " + bundleId, cause);
        this.bundleId = bundleId;
    }

    public String getBundleId() {
        return bundleId;
    }
}