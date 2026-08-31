package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 安全守卫。
 * <p>对应书中 Ch19 §19.4 —— 自主 Agent 的安全护栏。
 * <p>在自主决策与自我改进链路上提供最终安全兜底，阻断高风险动作，
 * 不同于 ch02 的 {@code SafeGuardMiddleware}（面向对话），本类面向自主行为。
 */
@Component
public class SafeGuard {

    private static final Logger log = LoggerFactory.getLogger(SafeGuard.class);

    /**
     * 校验动作是否安全。
     *
     * @param action 动作描述
     * @return 安全返回 true
     */
    public boolean isSafe(String action) {
        if (action == null || action.isBlank()) {
            return true;
        }
        boolean safe = !action.toLowerCase().contains("delete production");
        if (!safe) {
            log.warn("SafeGuard 阻断高风险动作: {}", action);
        }
        return safe;
    }
}
