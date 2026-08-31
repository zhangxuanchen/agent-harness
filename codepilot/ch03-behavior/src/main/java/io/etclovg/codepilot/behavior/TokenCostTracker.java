package io.etclovg.codepilot.behavior;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话级 Token 成本追踪器。
 * <p>对应书中 Ch03 §3.4 —— 成本感知编排的基础设施。
 * <p>按会话统计输入/输出 Token 用量与对应费用，供成本感知中间件
 * （如 {@code CostAwareOrchestrationMiddleware}）在编排决策中参考，
 * 并在预算超限时触发降级或人工介入。
 */
@Component
public class TokenCostTracker {

    private static final Logger log = LoggerFactory.getLogger(TokenCostTracker.class);

    /** 输入 Token 单价（美元/1K tokens） */
    private static final double INPUT_PRICE_PER_1K = 0.15;
    /** 输出 Token 单价（美元/1K tokens） */
    private static final double OUTPUT_PRICE_PER_1K = 0.60;

    private final Map<String, SessionUsage> sessions = new ConcurrentHashMap<>();

    /**
     * 记录一次调用的 Token 用量。
     *
     * @param sessionId    会话 ID
     * @param inputTokens  输入 Token 数
     * @param outputTokens 输出 Token 数
     */
    public void record(String sessionId, long inputTokens, long outputTokens) {
        SessionUsage usage = sessions.computeIfAbsent(sessionId, k -> new SessionUsage());
        usage.inputTokens.addAndGet(inputTokens);
        usage.outputTokens.addAndGet(outputTokens);
        log.debug("记录 Token 用量: session={}, in={}, out={}", sessionId, inputTokens, outputTokens);
    }

    /**
     * 获取会话累计费用（美元）。
     *
     * @param sessionId 会话 ID
     * @return 累计费用
     */
    public double getCost(String sessionId) {
        SessionUsage usage = sessions.get(sessionId);
        if (usage == null) {
            return 0.0;
        }
        return usage.inputTokens.get() / 1000.0 * INPUT_PRICE_PER_1K
                + usage.outputTokens.get() / 1000.0 * OUTPUT_PRICE_PER_1K;
    }

    /**
     * 获取会话累计的 Token 用量。
     *
     * @param sessionId 会话 ID
     * @return 包含 inputTokens 与 outputTokens 的快照
     */
    public UsageSnapshot getUsage(String sessionId) {
        SessionUsage usage = sessions.get(sessionId);
        if (usage == null) {
            return new UsageSnapshot(0, 0);
        }
        return new UsageSnapshot(usage.inputTokens.get(), usage.outputTokens.get());
    }

    /**
     * 清除指定会话的统计。
     *
     * @param sessionId 会话 ID
     */
    public void reset(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Token 用量快照。
     *
     * @param inputTokens  输入 Token 总数
     * @param outputTokens 输出 Token 总数
     */
    public record UsageSnapshot(long inputTokens, long outputTokens) {
    }

    private static final class SessionUsage {
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
    }
}
