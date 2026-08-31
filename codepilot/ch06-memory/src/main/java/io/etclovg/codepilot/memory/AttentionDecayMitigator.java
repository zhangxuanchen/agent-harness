package io.etclovg.codepilot.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 注意力衰减缓解器（C 层中间件）。
 *
 * <p>配套仓库教学实现，非 AgentScope 内置。
 *
 * <p>对应书中 KP 6.1.2 注意力衰减位置策略 —— 利用 Transformer 头部/尾部
 * 注意力峰值（primacy / recency effect）的关键约束放置策略，缓解长上下文中的
 * "中间遗忘"问题：序列中间的内容注意力最弱，最易被遗忘。
 */
@Component
public class AttentionDecayMitigator extends AbstractLayerMiddleware {

    public AttentionDecayMitigator() {
        super(Layer.C, "AttentionDecayMitigator");
    }

    /**
     * Acting 阶段：工具执行后检查是否需要重注入约束。
     *
     * <p>每轮工具调用都会把上下文整体向后推移，原本处于 recency 峰值的关键约束
     * 会被推入衰减区。在此阶段记录工具名，便于配合
     * {@link #buildReinforcementReminder} 按轮次周期性重注入。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        // 工具名从 RuntimeContext 取（与全书 T/C 层中间件一致）
        String toolName = rc.getExtra().getOrDefault("tool.name", "unknown").toString();
        log.debug("[AttentionDecay] 工具[{}]执行后检查约束是否需重注入", toolName);
        return next.apply(input);
    }

    /**
     * 把关键约束放到系统提示末尾。
     *
     * <p>为什么这样做：Transformer 对序列头部（primacy）和尾部（recency）注意力最强，
     * 中间最弱。系统提示末尾紧接最新 user message，是模型进入推理前"最后看到"的内容，
     * 把关键约束放这里能最大化 recency 加成，避免被中间大量工具历史稀释。
     * 对应策略 {@link PositionStrategy#TAIL}。
     *
     * @param systemPrompt 原始系统提示
     * @param constraint   关键约束文本
     * @return 注入约束后的系统提示
     */
    public String placeCriticalConstraintAtEnd(String systemPrompt, String constraint) {
        if (constraint == null || constraint.isEmpty()) {
            return systemPrompt;
        }
        String base = (systemPrompt == null) ? "" : systemPrompt;
        // 末尾追加约束块，用标记符突出，强化模型对边界的感知
        return base + "\n\n[关键约束·必须遵守]\n" + constraint;
    }

    /**
     * 把次重要信息格式化为要点列表。
     *
     * <p>为什么这样做：要点列表的结构化标记（- / *）让模型在扫描时形成离散锚点，
     * 每个要点独立成行，避免长段落中关键信息被注意力衰减吞没；同时降低 token 占用，
     * 让每个要点都能落在局部注意力峰值上。
     *
     * @param items 次重要信息条目
     * @return 格式化后的要点列表文本
     */
    public String formatAsBulletList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return items.stream()
                .filter(item -> item != null && !item.isEmpty())
                .map(item -> "- " + item)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 把长文本（日志、代码）放到 user message 末端。
     *
     * <p>为什么这样做：长文本本身是注意力衰减的重灾区（中间内容易被遗忘），
     * 但它又必须被模型"看到"。放到 user message 末端利用 recency 峰值，
     * 让模型在生成回答前最近一次接触到完整长文本，降低遗漏风险。
     * 对应策略 {@link PositionStrategy#USER_TAIL}。
     *
     * @param userMessage 原始 user message
     * @param longText    长文本（日志/代码）
     * @return 拼接后的 user message
     */
    public String placeLongTextAtEnd(String userMessage, String longText) {
        if (longText == null || longText.isEmpty()) {
            return userMessage;
        }
        String base = (userMessage == null) ? "" : userMessage;
        return base + "\n\n[附·长文本]\n" + longText;
    }

    /**
     * 生成周期性约束重注入提醒。
     *
     * <p>为什么这样做：长对话中即使约束放在末尾，也会随轮次推进被推离 recency 峰值
     * 而进入衰减区。每 N 轮把约束以"提醒"形式重新追加到 user message 末尾，
     * 相当于周期性把它推回注意力峰值区，对抗随轮次累积的衰减。
     *
     * @param constraint 关键约束
     * @param interval   重注入轮次间隔（每 N 轮一次）
     * @return 追加到 user message 末尾的重注入提醒文本
     */
    public String buildReinforcementReminder(String constraint, int interval) {
        if (constraint == null || constraint.isEmpty()) {
            return "";
        }
        // 间隔非正则按 1 轮兜底，避免无限等待
        int safeInterval = interval <= 0 ? 1 : interval;
        return "\n\n[周期提醒·每 " + safeInterval + " 轮重注入]\n"
                + "请确认仍遵守以下约束：" + constraint;
    }

    /**
     * 位置策略枚举。
     *
     * <p>对应注意力曲线的三个关键放置点，每个点利用不同的注意力峰值机制。
     */
    public enum PositionStrategy {
        /** 系统提示开头：利用 primacy 峰值，放置稳定不变的核心人格/规则 */
        HEAD("系统提示开头·primacy 峰值"),
        /** 系统提示末尾：利用 recency 峰值，放置关键约束（紧接最新 user message 之前） */
        TAIL("系统提示末尾·recency 峰值"),
        /** user message 末尾：放置长文本与周期重注入提醒，获得最强 recency */
        USER_TAIL("user message 末尾·最新 recency");

        private final String description;

        PositionStrategy(String description) {
            this.description = description;
        }

        /** 策略说明 */
        public String description() {
            return description;
        }
    }
}
