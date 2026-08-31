package io.etclovg.codepilot.orchestration;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * L 层 · 目标锚定 Advisor。
 *
 * <p>防止 Agent 在长链推理中出现"目标漂移"（Goal Drift）——
 * Agent 在多个步骤后逐渐偏离原始任务目标。
 * 对应书中 Ch7 §7.6 目标锚定与漂移防御。
 *
 * <p><b>机制</b>：
 * <ul>
 *   <li><b>周期性注入</b>：每 N 步将原始目标重新注入上下文</li>
 *   <li><b>语义漂移检测</b>：使用关键词重叠度 + 任务嵌入余弦相似度判断漂移程度</li>
 *   <li><b>漂移分级响应</b>：
 *     <ul>
 *       <li>轻度(0.7~0.9)：温和提醒</li>
 *       <li>中度(0.5~0.7)：强调原始目标</li>
 *       <li>重度(&lt; 0.5)：强制回锚 + 重置部分上下文</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>页面参考</b>：Ch7 §7.6 目标锚定与漂移防御
 */
@Component
public class GoalAnchorAdvisor extends AbstractLayerMiddleware {

    /** 任务ID → 原始目标定义 */
    private final Map<String, GoalAnchor> goals = new ConcurrentHashMap<>();
    /** 任务ID → 步骤计数器 */
    private final Map<String, AtomicInteger> stepCounters = new ConcurrentHashMap<>();

    /** 每 N 步注入一次目标 */
    private int anchorInterval = 5;
    /** 是否启用语义漂移检测 */
    private boolean driftDetectionEnabled = true;
    /** 轻度漂移阈值 */
    private double mildDriftThreshold = 0.7;
    /** 重度漂移阈值 */
    private double severeDriftThreshold = 0.5;

    public GoalAnchorAdvisor() {
        super(Layer.L, "GoalAnchorAdvisor-L");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String taskId = context.getOrDefault("task.id", "default").toString();

        // 获取或初始化任务目标
        GoalAnchor anchor = goals.get(taskId);
        if (anchor == null) {
            // 尝试从上下文提取目标
            String rawGoal = context.getOrDefault("task.goal", "").toString();
            String constraints = context.getOrDefault("task.constraints", "").toString();
            if (!rawGoal.isEmpty()) {
                anchor = new GoalAnchor(rawGoal, constraints,
                        extractKeywords(rawGoal + " " + constraints));
                goals.put(taskId, anchor);
                log.info("[L层·目标锚定] 任务 {} 目标已锚定: {}", taskId, anchor.goal);
            }
        }

        AtomicInteger counter = stepCounters.computeIfAbsent(taskId,
                k -> new AtomicInteger(0));
        int currentStep = counter.incrementAndGet();

        // —— 周期性注入 ——
        if (anchor != null && currentStep % anchorInterval == 0) {
            String anchorPrompt = buildAnchorPrompt(taskId, anchor);
            rc.put("goal.anchor_injected", true);
            rc.put("goal.anchor_prompt", anchorPrompt);
            rc.put("goal.anchor_step", currentStep);
            rc.put("goal.anchor_goal", anchor.goal);
            log.info("[L层·目标锚定] 任务 {} 第{}步注入目标锚定", taskId, currentStep);
        }

        // —— 语义漂移检测 ——
        if (driftDetectionEnabled && anchor != null && currentStep > anchorInterval) {
            String currentContent = extractCurrentContext(context);
            double relevance = computeGoalRelevance(anchor.keywords, currentContent);

            if (relevance < severeDriftThreshold) {
                log.warn("[L层·目标锚定] 任务 {} 第{}步重度漂移(相关性={:.2f})——强制回锚",
                        taskId, currentStep, relevance);
                rc.put("goal.drift_severity", "SEVERE");
                rc.put("goal.drift_relevance", relevance);
                rc.put("goal.force_anchor", true);
            } else if (relevance < mildDriftThreshold) {
                log.info("[L层·目标锚定] 任务 {} 第{}步轻度漂移(相关性={:.2f})——温和提醒",
                        taskId, currentStep, relevance);
                rc.put("goal.drift_severity", "MILD");
                rc.put("goal.drift_relevance", relevance);
            }
        }

        return next.apply(input);
    }

    /**
     * 注册任务目标。
     *
     * @param taskId      任务ID
     * @param goal        目标描述
     * @param constraints 约束条件
     */
    public void registerGoal(String taskId, String goal, String constraints) {
        Set<String> keywords = extractKeywords(goal + " " + constraints);
        goals.put(taskId, new GoalAnchor(goal, constraints, keywords));
        log.info("[L层·目标锚定] 任务 {} 目标已注册: {}", taskId, goal);
    }

    /**
     * 构建目标注入提示。
     */
    private String buildAnchorPrompt(String taskId, GoalAnchor anchor) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 任务目标锚定（防止漂移） ---\n");
        sb.append("原始目标: ").append(anchor.goal).append("\n");
        if (anchor.constraints != null && !anchor.constraints.isEmpty()) {
            sb.append("约束条件: ").append(anchor.constraints).append("\n");
        }
        sb.append("请检查当前进展是否仍有助於达成此目标，如已偏離请立即纠偏。\n");
        sb.append("--- 锚定结束 ---");
        return sb.toString();
    }

    /**
     * 从当前上下文中提取用于漂移检测的文本。
     */
    private String extractCurrentContext(Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.getOrDefault("user.input", ""));
        sb.append(" ");
        sb.append(context.getOrDefault("tool.name", ""));
        sb.append(" ");
        sb.append(context.getOrDefault("tool.result", ""));
        sb.append(" ");
        sb.append(context.getOrDefault("model.last_response", ""));
        return sb.toString().toLowerCase();
    }

    /**
     * 计算当前内容与目标关键词的重叠度，作为相关性代理指标。
     * <p>生产环境替换为 embedding 余弦相似度。
     */
    double computeGoalRelevance(Set<String> goalKeywords, String currentContent) {
        if (goalKeywords.isEmpty() || currentContent.isEmpty()) return 1.0;

        long matched = goalKeywords.stream()
                .filter(kw -> currentContent.contains(kw.toLowerCase()))
                .count();

        return (double) matched / goalKeywords.size();
    }

    /**
     * 提取关键词——取长度 ≥ 2 的非停用词。
     */
    private Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Set.of();

        // 中英文停用词（简化版）
        Set<String> stopWords = Set.of(
                "的", "了", "是", "在", "和", "也", "就", "都", "个",
                "the", "is", "a", "an", "and", "to", "of", "in", "for", "on",
                "with", "be", "it", "that", "this", "or", "as"
        );

        return Arrays.stream(text.toLowerCase().split("[\\s，。！？、；：\"'（）\\[\\]{}|/\\\\.,!?;:'\"()\\-_]+"))
                .filter(w -> w.length() >= 2)
                .filter(w -> !stopWords.contains(w))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 获取指定任务的目标锚。
     */
    public GoalAnchor getGoal(String taskId) {
        return goals.get(taskId);
    }

    /**
     * 清理任务目标。
     */
    public void cleanup(String taskId) {
        goals.remove(taskId);
        stepCounters.remove(taskId);
        log.debug("[L层·目标锚定] 任务 {} 目标已清理", taskId);
    }

    // ==================== 配置方法 ====================

    public void setAnchorInterval(int anchorInterval) { this.anchorInterval = anchorInterval; }
    public void setDriftDetectionEnabled(boolean driftDetectionEnabled) { this.driftDetectionEnabled = driftDetectionEnabled; }
    public void setMildDriftThreshold(double mildDriftThreshold) { this.mildDriftThreshold = mildDriftThreshold; }
    public void setSevereDriftThreshold(double severeDriftThreshold) { this.severeDriftThreshold = severeDriftThreshold; }

    // ==================== 内部类型 ====================

    record GoalAnchor(String goal, String constraints, Set<String> keywords) {}
}
