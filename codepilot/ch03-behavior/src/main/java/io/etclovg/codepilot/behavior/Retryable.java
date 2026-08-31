package io.etclovg.codepilot.behavior;

/**
 * 可重试接口——解耦 V 层（验证）和 L 层（编排）的显式依赖。
 * <p>对应书中 Ch03 §3.4.4 耦合治理——接口抽象模式。
 *
 * <p>V 层只依赖此接口而非 L 层具体实现，L 层实现此接口。
 * 改 L 层的重试逻辑只要不破坏 {@code Retryable} 的契约，V 层无需改动。
 *
 * @see RetryOrchestrationMiddleware
 */
public interface Retryable {

    /**
     * 触发指定会话的重试。
     *
     * @param sessionId 会话 ID
     */
    void retry(String sessionId);
}