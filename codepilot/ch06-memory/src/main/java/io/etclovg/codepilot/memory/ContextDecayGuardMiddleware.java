package io.etclovg.codepilot.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 上下文腐烂治理中间件。
 * 配套仓库教学实现，非 AgentScope 内置。
 *
 * 对应书中 KP 6.4.1 "上下文腐烂：看得见但提取不出来"的治理策略。
 * 挂载在 Acting 阶段，每步工具调用前执行：
 *   1. 诊断：调用 ContextDecayDiagnostic 计算衰减分数
 *   2. 写入侧治理一：衰减超阈值时调用 ContextCompactor 压缩历史
 *   3. 写入侧治理二：每 N 步用 AnchorFilter 筛选关键信息，追加到 user message
 *
 * 继承 AbstractLayerMiddleware（codepilot-core 包），不匿名实现 MiddlewareBase 接口。
 */
@Component
public class ContextDecayGuardMiddleware extends AbstractLayerMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ContextDecayGuardMiddleware.class);

    /** 衰减告警阈值：totalDecayScore > 此值说明早期信息已被噪声淹没 */
    private static final double DECAY_THRESHOLD = 0.6;

    /** 锚定频率：每 N 步做一次关键信息锚定 */
    private static final int ANCHOR_INTERVAL = 5;

    private final ContextDecayDiagnostic diagnostic;
    private final ContextCompactor compactor;
    private final AnchorFilter anchorFilter;

    /** 步计数器：用于控制锚定周期 */
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    /** 构造器注入：三个依赖都是必需的 */
    public ContextDecayGuardMiddleware(ContextDecayDiagnostic diagnostic,
                                       ContextCompactor compactor,
                                       AnchorFilter anchorFilter) {
        super(Layer.C, "ContextDecayGuardMiddleware");
        this.diagnostic = diagnostic;
        this.compactor = compactor;
        this.anchorFilter = anchorFilter;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent,
                                     RuntimeContext rc,
                                     ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String toolName = rc.getExtra().getOrDefault("tool.name", "unknown").toString();
        int step = stepCounter.incrementAndGet();

        return Flux.defer(() -> {
            // 步骤 1：诊断当前衰减情况
            DecayDiagnosticResult decay = diagnostic.diagnose();
            log.info("[DecayGuard] 步 {} 工具 {}: 衰减总分 {:.2f}, 等级 {}, 丢失约束 {} 条",
                    step, toolName, decay.totalDecayScore(), decay.decayLevel(),
                    decay.lostConstraints().size() + decay.lostFacts().size());

            // 步骤 2：治理一（定期摘要）——衰减超阈值时调用压缩器
            if (decay.needsAlert(DECAY_THRESHOLD)) {
                log.warn("[DecayGuard] 衰减 {:.2f} > 阈值 {:.2f}，标记压缩需求（由管线下一步执行）",
                        decay.totalDecayScore(), DECAY_THRESHOLD);
                // 实际压缩由 WorkingMemoryManager / ContextCompactor 在主工作流节点触发，
                // 这里只做衰减等级标记，避免中间件里硬取 ActingInput 消息列表导致的接口耦合
                rc.getExtra().put("decay.compact.required", "true");
                rc.getExtra().put("decay.compact.score", decay.totalDecayScore());
            }

            // 步骤 3：治理二（关键信息锚定）——每 5 步从探针结果中抽取锚定点
            if (step % ANCHOR_INTERVAL == 0) {
                StringBuilder anchorText = new StringBuilder(
                        "\n\n【关键信息锚定——以下内容请牢记，KP 6.4.1 治理注入】\n");
                boolean foundAny = false;

                // 从探针结果里提取未丢失的约束和事实，再次用 AnchorFilter 二次筛选
                List<String> toAnchor = new ArrayList<>();
                toAnchor.addAll(decay.retainedConstraints());
                toAnchor.addAll(decay.retainedFacts());
                // 追加丢失的约束——因为"丢失"恰恰说明必须重新锚定
                toAnchor.addAll(decay.lostConstraints());
                // 追加探针结果
                for (DecayDiagnosticResult.ProbeResult pr : decay.probeResults()) {
                    if (!pr.matched() && pr.expectedAnswer() != null) {
                        toAnchor.add(pr.question() + " → " + pr.expectedAnswer());
                    }
                }

                for (String text : toAnchor) {
                    List<AnchorFilter.AnchorItem> items = anchorFilter.filter(text, toolName);
                    for (AnchorFilter.AnchorItem item : items) {
                        String prefix = item.isCritical() ? "[高风险高依赖] "
                                : item.highRisk() ? "[高风险] "
                                : item.highDependency() ? "[高依赖] " : "";
                        anchorText.append("• ").append(prefix).append(item.content()).append("\n");
                        foundAny = true;
                    }
                }

                if (foundAny) {
                    // ActingInput 没有直接追加方法，通过 RuntimeContext.extra 注入
                    rc.getExtra().put("decay.anchor.text", anchorText.toString());
                    log.info("[DecayGuard] 步 {} 完成锚定注入（通过 RuntimeContext.extra）", step);
                }
            }

            return next.apply(input);
        });
    }
}
