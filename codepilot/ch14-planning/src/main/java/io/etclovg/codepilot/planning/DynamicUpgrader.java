package io.etclovg.codepilot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 动态升级器。
 * <p>对应书中 Ch14 §14.2.2 —— 执行中发现范式不合适时的动态升级。
 * 如 ReAct 执行到第 6 步时发现需要更多结构，升级为 Plan-Execute。
 */
@Component
public class DynamicUpgrader {

    private static final Logger log = LoggerFactory.getLogger(DynamicUpgrader.class);

    /**
     * 动态升级条件：步数超限 + 可预测性丧失
     */
    public boolean shouldUpgrade(int currentStep, boolean predictable, int maxSteps) {
        return currentStep >= maxSteps && !predictable;
    }

    /**
     * 执行升级，返回新的范式。
     *
     * @param currentParadigm 当前范式
     * @param reason          升级原因
     * @return 升级后的范式
     */
    public ReasoningRouter.Paradigm upgrade(ReasoningRouter.Paradigm currentParadigm, String reason) {
        log.info("动态升级：{} → {}，原因：{}", currentParadigm, "PLAN_EXECUTE", reason);
        return ReasoningRouter.Paradigm.PLAN_EXECUTE;
    }
}
