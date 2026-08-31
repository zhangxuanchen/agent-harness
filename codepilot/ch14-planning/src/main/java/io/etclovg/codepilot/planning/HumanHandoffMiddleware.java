package io.etclovg.codepilot.planning;

import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 人机交接中间件（L 层）。
 * <p>对应书中 Ch14 §14.4.3 —— Agent 遇到不确定性且降级无法解决时，
 * 自动交接给人类用户继续决策。
 *
 * <p>与章节代码对齐：继承 {@link AbstractLayerMiddleware}，实现 shouldHandoff 三条件
 * （置信度 < 0.3 / 连续失败 > 3 / 置信度连续下降），交接时在 RuntimeContext 注入
 * handoff 信号和结构化交接信息。
 */
@Component
public class HumanHandoffMiddleware extends AbstractLayerMiddleware {

    private static final Logger log = LoggerFactory.getLogger(HumanHandoffMiddleware.class);

    /** 默认置信度阈值：低于此值触发交接 */
    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.3;
    /** 默认连续失败次数阈值 */
    private static final int DEFAULT_MAX_FAILURES = 3;
    /** 默认置信度下降次数阈值 */
    private static final int DEFAULT_CONFIDENCE_DROP_THRESHOLD = 3;

    private double confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
    private int maxFailures = DEFAULT_MAX_FAILURES;
    private int confidenceDropThreshold = DEFAULT_CONFIDENCE_DROP_THRESHOLD;

    public HumanHandoffMiddleware() {
        super(Layer.L, "human-handoff");
    }

    /**
     * 设置置信度阈值。
     */
    public void setConfidenceThreshold(double threshold) {
        this.confidenceThreshold = threshold;
    }

    /**
     * 设置最大连续失败次数。
     */
    public void setMaxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    /**
     * 判断是否应触发人机交接。
     *
     * @param confidence         当前置信度
     * @param consecutiveFailures 连续失败次数
     * @param confidenceTrend    近期置信度序列（用于检测下降趋势）
     * @param highRisk           当前操作是否高风险
     * @return 应交接返回 true
     */
    public boolean shouldHandoff(double confidence, int consecutiveFailures,
                                  List<Double> confidenceTrend, boolean highRisk) {
        // 条件 1: 低置信度 + 高风险操作（AND 关系——低置信但无风险的操作不触发交接）
        if (confidence < confidenceThreshold && highRisk) {
            log.info("触发交接：置信度 {} < 阈值 {} 且高风险", confidence, confidenceThreshold);
            return true;
        }
        // 条件 2: 连续失败次数超过阈值（无论风险等级）
        if (consecutiveFailures >= maxFailures) {
            log.info("触发交接：连续失败 {} 次", consecutiveFailures);
            return true;
        }
        // 条件 3: 置信度连续下降趋势
        if (confidenceTrend != null && confidenceTrend.size() >= confidenceDropThreshold) {
            boolean monotonicallyDecreasing = true;
            for (int i = 1; i < confidenceTrend.size(); i++) {
                if (confidenceTrend.get(i) >= confidenceTrend.get(i - 1)) {
                    monotonicallyDecreasing = false;
                    break;
                }
            }
            if (monotonicallyDecreasing) {
                log.info("触发交接：置信度连续下降");
                return true;
            }
        }
        return false;
    }

    /**
     * 生成交接上下文。
     *
     * @param taskSummary     任务摘要
     * @param completedSteps  已完成步骤
     * @param stuckAt         卡在哪一步
     * @param errorDetail     错误详情
     * @return 结构化交接信息
     */
    public HandoffContext buildHandoffContext(
            String taskSummary,
            List<String> completedSteps,
            String stuckAt,
            String errorDetail) {
        List<HandoffContext.Choice> choices = List.of(
            new HandoffContext.Choice("手动继续", "CONTINUE_MANUAL", "手动处理"),
            new HandoffContext.Choice("修改参数重试", "RETRY_WITH_FEEDBACK", "给反馈重试"),
            new HandoffContext.Choice("终止任务", "TERMINATE", "终止")
        );
        return new HandoffContext(taskSummary, completedSteps, stuckAt, errorDetail, choices);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // 从 RuntimeContext 读取置信度、失败次数和风险等级（实际由上游中间件注入）
        Object confObj = rc.get("planning.confidence");
        Object failObj = rc.get("planning.consecutiveFailures");
        Object riskObj = rc.get("planning.isHighRisk");
        boolean highRisk = riskObj instanceof Boolean r && r;
        if (confObj instanceof Double conf && failObj instanceof Integer fails) {
            if (shouldHandoff(conf.doubleValue(), fails.intValue(), null, highRisk)) {
                // 设置 handoff 信号
                rc.put("handoff.triggered", true);
                rc.put("handoff.reason", "置信度不足或连续失败");
                log.info("人机交接已触发, 置信度={}, 连续失败={}, 高风险={}", conf, fails, highRisk);
                // 返回空 Flux 表示不继续执行
                return Flux.empty();
            }
        }
        return next.apply(input);
    }
}
