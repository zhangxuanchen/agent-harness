package io.etclovg.codepilot.definition;

import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 拒绝守护。
 * <p>对应书中 Ch02 §2.5.1 —— 判断请求或响应是否应被安全拒绝。
 *
 * <p>已从 Spring AI {@code ChatClientRequest} 迁移为 AgentScope {@link RuntimeContext}，
 * 从上下文属性 {@code user.message} 读取待检测文本。
 */
@Component
public class RefusalGuard {

    private static final Logger log = LoggerFactory.getLogger(RefusalGuard.class);

    private final Set<String> blockedKeywords = Set.of(
            "dangerous", "harmful", "exploit", "malware"
    );

    /**
     * 判断请求是否应该被拒绝。
     */
    public boolean shouldRefuse(RuntimeContext rc) {
        String userMessage = rc.getExtra().getOrDefault("user.message", "").toString();
        for (String keyword : blockedKeywords) {
            if (userMessage.toLowerCase().contains(keyword)) {
                log.warn("[RefusalGuard] 检测到阻断关键词: {}", keyword);
                return true;
            }
        }
        return false;
    }

    /**
     * 判断响应内容是否安全。
     */
    public boolean isResponseContentSafe(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        for (String keyword : blockedKeywords) {
            if (content.toLowerCase().contains(keyword)) {
                log.warn("[RefusalGuard] 响应中检测到不安全内容: {}", keyword);
                return false;
            }
        }
        return true;
    }
}
