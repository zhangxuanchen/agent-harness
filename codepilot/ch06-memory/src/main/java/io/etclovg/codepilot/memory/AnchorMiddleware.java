package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文锚定中间件。
 * <p>对应书中 Ch06 §6.2 —— 上下文记忆层的锚定机制。
 * <p>在每轮对话开始时将目标、关键约束与历史决策重新注入上下文，
 * 防止长对话中目标漂移，是 {@code GoalAnchorMiddleware} 在记忆层的延伸。
 */
@Component
public class AnchorMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AnchorMiddleware.class);

    /**
     * 构建锚定上下文片段。
     *
     * @param sessionContext 会话上下文
     * @return 锚定片段列表
     */
    public List<String> buildAnchors(SessionContext sessionContext) {
        log.debug("构建锚定上下文: session={}", sessionContext.getSessionId());
        return List.of();
    }

    /**
     * 将锚定片段注入原始上下文。
     *
     * @param originalContext 原始上下文文本
     * @param anchors         锚定片段
     * @return 注入后的上下文
     */
    public String inject(String originalContext, List<String> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return originalContext;
        }
        return String.join("\n", anchors) + "\n" + originalContext;
    }
}
