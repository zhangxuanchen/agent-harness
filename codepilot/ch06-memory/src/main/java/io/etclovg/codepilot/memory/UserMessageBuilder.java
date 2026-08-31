package io.etclovg.codepilot.memory;

import org.springframework.stereotype.Component;

/**
 * 用户消息构建器。
 * <p>对应书中 Ch06 §6.3 —— 动态信息放在 user message 中，不放在 system prompt 中，
 * 确保 KV-cache 的稳定前缀不受影响。
 */
@Component
public class UserMessageBuilder {

    /**
     * 构建包含会话上下文和当前时间的用户消息。
     *
     * @param query 用户查询
     * @param ctx   会话上下文
     * @return 格式化后的用户消息字符串
     */
    public String buildUserMessage(String query, SessionContext ctx) {
        return """
            [Session: %s]
            [Current time: %s]
            User query: %s
            """.formatted(ctx.getSessionId(), ctx.getCreatedAt(), query);
    }
}