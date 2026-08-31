package io.etclovg.codepilot.memory;

import io.etclovg.codepilot.core.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * C 层 · 上下文衰减诊断器。
 *
 * <p>实现 Ch6 §6.6 定义的上下文腐烂（Context Decay）检测机制——
 * 每隔 N 步注入探针验证 Agent 是否还记得最初的目标，并提供衰减度诊断。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>探针注入</b>：定期注入探针问题，验证目标记忆</li>
 *   <li><b>衰减度计算</b>：根据探针响应计算衰减度 [0.0-1.0]</li>
 *   <li><b>关键信息追踪</b>：追踪关键约束和事实是否还在上下文中</li>
 *   <li><b>告警机制</b>：衰减度 > 阈值时自动告警</li>
 *   <li><b>建议生成</b>：根据衰减程度提供恢复建议</li>
 * </ul>
 *
 * <h3>探针注入机制</h3>
 * <pre>
 * ┌────────────────────────────────────────┐
 * │  Step 1  Step 5  Step 10  Step 15 ... │
 * │    ↓                          ↓        │
 * │  [注入探针]                 [注入探针]  │
 * │    ↓                          ↓        │
 * │  [验证响应]                 [验证响应]  │
 * │    ↓                          ↓        │
 * │  [更新衰减度]               [更新衰减度] │
 * └────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ContextDecayDiagnostic diagnostic = new ContextDecayDiagnostic();
 *
 * // 注册探针
 * diagnostic.registerProbe(DecayProbe.goalRecall(
 *     "实现用户登录功能",
 *     "请复述当前任务目标"
 * ));
 *
 * // 每步检查是否需要注入探针
 * if (diagnostic.shouldInjectProbe(currentStep)) {
 *     DecayProbe probe = diagnostic.selectProbe(currentStep);
 *     String response = agent.execute(probe.question());
 *     diagnostic.recordProbeResponse(probe, response);
 * }
 *
 * // 执行诊断
 * DecayDiagnosticResult result = diagnostic.diagnose();
 * if (result.needsAlert(0.6)) {
 *     log.warn(result.generateAlertMessage());
 * }
 * }</pre>
 *
 * <p><b>页面参考</b>：Ch6 §6.6 上下文腐烂检测 / §6.7.2 信息保真度评测
 *
 * @see DecayProbe
 * @see DecayDiagnosticResult
 */
@Component
public class ContextDecayDiagnostic {

    private static final Logger log = LoggerFactory.getLogger(ContextDecayDiagnostic.class);

    // ==================== 配置参数 ====================

    /** 默认注入频率（每隔 N 步注入一次） */
    private int defaultInjectionFrequency = 10;

    /** 衰减告警阈值（默认 0.6） */
    private double alertThreshold = 0.6;

    /** 严重衰减阈值（默认 0.8） */
    private double criticalThreshold = 0.8;

    /** 探针响应时间窗口（只统计最近 N 步内的响应） */
    private int responseWindowSteps = 20;

    // ==================== 核心数据结构 ====================

    /** 探针注册表（探针 ID -> 探针） */
    private final Map<String, DecayProbe> probeRegistry = new ConcurrentHashMap<>();

    /** 探针响应记录（探针 ID -> 响应列表） */
    private final Map<String, List<ProbeResponse>> probeResponses = new ConcurrentHashMap<>();

    /** 当前步数计数器 */
    private final AtomicInteger currentStep = new AtomicInteger(0);

    /** 关键约束追踪（约束 -> 是否仍然保留） */
    private final Map<String, Boolean> constraintTracker = new ConcurrentHashMap<>();

    /** 关键事实追踪（事实 -> 是否仍然保留） */
    private final Map<String, Boolean> factTracker = new ConcurrentHashMap<>();

    /** 缓存的最近诊断结果 */
    private volatile DecayDiagnosticResult cachedResult = null;

    /** 统计信息 */
    private final AtomicInteger totalProbesInjected = new AtomicInteger(0);
    private final AtomicInteger totalResponsesRecorded = new AtomicInteger(0);

    // ==================== 探针注册 ====================

    /**
     * 注册一个探针。
     *
     * @param probe 探针
     */
    public void registerProbe(DecayProbe probe) {
        if (probe == null) {
            log.warn("[C层·衰减] 尝试注册 null 探针，已忽略");
            return;
        }

        probeRegistry.put(probe.id(), probe);
        probeResponses.put(probe.id(), new ArrayList<>());

        log.info("[C层·衰减] 注册探针: id={}, type={}, priority={}, frequency={}",
                probe.id(), probe.type(), probe.priority(), probe.injectionFrequency());
    }

    /**
     * 注册多个探针。
     *
     * @param probes 探针列表
     */
    public void registerProbes(List<DecayProbe> probes) {
        if (probes != null) {
            probes.forEach(this::registerProbe);
        }
    }

    /**
     * 注销一个探针。
     *
     * @param probeId 探针 ID
     */
    public void unregisterProbe(String probeId) {
        probeRegistry.remove(probeId);
        probeResponses.remove(probeId);

        log.debug("[C层·衰减] 注销探针: id={}", probeId);
    }

    /**
     * 清空所有探针。
     */
    public void clearProbes() {
        probeRegistry.clear();
        probeResponses.clear();

        log.info("[C层·衰减] 清空所有探针");
    }

    // ==================== 关键信息追踪 ====================

    /**
     * 注册关键约束（将被追踪是否保留）。
     *
     * @param constraint 关键约束
     */
    public void registerConstraint(String constraint) {
        if (constraint != null && !constraint.isEmpty()) {
            constraintTracker.put(constraint, true);  // 初始假设保留
            log.debug("[C层·衰减] 注册关键约束: {}", constraint);
        }
    }

    /**
     * 注册关键事实（文件路径、端口号等）。
     *
     * @param fact 关键事实
     */
    public void registerFact(String fact) {
        if (fact != null && !fact.isEmpty()) {
            factTracker.put(fact, true);  // 初始假设保留
            log.debug("[C层·衰减] 注册关键事实: {}", fact);
        }
    }

    /**
     * 标记约束为丢失。
     *
     * @param constraint 约束
     */
    public void markConstraintLost(String constraint) {
        constraintTracker.put(constraint, false);
    }

    /**
     * 标记事实为丢失。
     *
     * @param fact 事实
     */
    public void markFactLost(String fact) {
        factTracker.put(fact, false);
    }

    // ==================== 探针注入与响应记录 ====================

    /**
     * 推进步数并检查是否需要注入探针。
     *
     * @return 如果需要注入探针，返回 true
     */
    public boolean shouldInjectProbe() {
        int step = currentStep.incrementAndGet();
        return shouldInjectProbe(step);
    }

    /**
     * 检查指定步数是否需要注入探针。
     *
     * @param step 当前步数
     * @return 如果需要注入探针，返回 true
     */
    public boolean shouldInjectProbe(int step) {
        if (probeRegistry.isEmpty()) {
            return false;
        }

        // 检查是否有探针需要在此步注入
        return probeRegistry.values().stream()
                .anyMatch(probe -> probe.shouldInject(step));
    }

    /**
     * 选择需要在当前步注入的探针。
     *
     * @param step 当前步数
     * @return 需要注入的探针列表
     */
    public List<DecayProbe> selectProbes(int step) {
        return probeRegistry.values().stream()
                .filter(probe -> probe.shouldInject(step))
                .sorted(Comparator.comparingInt(DecayProbe::priority).reversed())  // 高优先级优先
                .toList();
    }

    /**
     * 选择最高优先级的探针。
     *
     * @param step 当前步数
     * @return 探针（无探针需要注入时返回 null）
     */
    public DecayProbe selectProbe(int step) {
        List<DecayProbe> probes = selectProbes(step);
        return probes.isEmpty() ? null : probes.get(0);
    }

    /**
     * 记录探针响应。
     *
     * @param probe 探针
     * @param response Agent 的响应
     */
    public void recordProbeResponse(DecayProbe probe, String response) {
        if (probe == null || response == null) {
            return;
        }

        // 记录响应
        List<ProbeResponse> responses = probeResponses.computeIfAbsent(
                probe.id(), k -> new ArrayList<>());
        responses.add(new ProbeResponse(
                response,
                Instant.now(),
                currentStep.get()
        ));

        // 限制响应窗口大小
        if (responses.size() > responseWindowSteps) {
            responses.remove(0);
        }

        totalResponsesRecorded.incrementAndGet();

        log.debug("[C层·衰减] 记录探针响应: probeId={}, matchScore={:.2f}",
                probe.id(), probe.calculateMatchScore(response));
    }

    /**
     * 注入探针并记录响应的便捷方法。
     *
     * @param probe 探针
     * @param response Agent 的响应
     * @return 匹配分数
     */
    public double injectAndRecord(DecayProbe probe, String response) {
        totalProbesInjected.incrementAndGet();
        recordProbeResponse(probe, response);
        return probe.calculateMatchScore(response);
    }

    // ==================== 诊断方法 ====================

    /**
     * 执行衰减诊断。
     *
     * <p>诊断过程：
     * <ol>
     *   <li>计算所有探针的匹配分数</li>
     *   <li>汇总关键约束和事实的保留情况</li>
     *   <li>计算总体衰减度</li>
     *   <li>生成建议措施</li>
     * </ol>
     *
     * @return 诊断结果
     */
    public DecayDiagnosticResult diagnose() {
        log.info("[C层·衰减] 开始诊断: 步数={}, 探针数={}, 约束数={}, 事实数={}",
                currentStep.get(), probeRegistry.size(),
                constraintTracker.size(), factTracker.size());

        // 1. 计算探针匹配分数
        List<DecayDiagnosticResult.ProbeResult> probeResults = new ArrayList<>();
        double totalMatchScore = 0.0;
        int probeCount = 0;

        for (Map.Entry<String, DecayProbe> entry : probeRegistry.entrySet()) {
            DecayProbe probe = entry.getValue();
            List<ProbeResponse> responses = probeResponses.get(entry.getKey());

            if (responses != null && !responses.isEmpty()) {
                // 取最近的响应
                ProbeResponse lastResponse = responses.get(responses.size() - 1);
                double matchScore = probe.calculateMatchScore(lastResponse.response);

                probeResults.add(new DecayDiagnosticResult.ProbeResult(
                        probe.id(),
                        probe.type(),
                        probe.question(),
                        probe.expectedAnswer(),
                        lastResponse.response,
                        matchScore,
                        matchScore >= 0.5,  // 通过阈值
                        responses.size()
                ));

                totalMatchScore += matchScore;
                probeCount++;
            }
        }

        // 2. 汇总约束和事实保留情况
        List<String> retainedConstraints = new ArrayList<>();
        List<String> lostConstraints = new ArrayList<>();

        constraintTracker.forEach((constraint, retained) -> {
            if (retained) {
                retainedConstraints.add(constraint);
            } else {
                lostConstraints.add(constraint);
            }
        });

        List<String> retainedFacts = new ArrayList<>();
        List<String> lostFacts = new ArrayList<>();

        factTracker.forEach((fact, retained) -> {
            if (retained) {
                retainedFacts.add(fact);
            } else {
                lostFacts.add(fact);
            }
        });

        // 3. 计算总体衰减度
        double probeDecayScore = probeCount > 0 ? 1.0 - (totalMatchScore / probeCount) : 0.0;
        double constraintDecayScore = calculateConstraintDecayScore(retainedConstraints, lostConstraints);
        double factDecayScore = calculateFactDecayScore(retainedFacts, lostFacts);

        // 综合衰减度 = 0.5 * probe + 0.3 * constraint + 0.2 * fact
        double totalDecayScore = 0.5 * probeDecayScore +
                                  0.3 * constraintDecayScore +
                                  0.2 * factDecayScore;

        // 4. 确定衰减等级
        DecayDiagnosticResult.DecayLevel decayLevel =
                DecayDiagnosticResult.DecayLevel.fromScore(totalDecayScore);

        // 5. 生成建议
        List<String> recommendations = generateRecommendations(
                totalDecayScore, lostConstraints, lostFacts);

        // 6. 构建诊断结果
        DecayDiagnosticResult result = new DecayDiagnosticResult(
                totalDecayScore,
                decayLevel,
                retainedConstraints,
                lostConstraints,
                retainedFacts,
                lostFacts,
                probeResults,
                recommendations,
                Instant.now(),
                currentStep.get(),
                Map.of(
                        "probe_decay_score", probeDecayScore,
                        "constraint_decay_score", constraintDecayScore,
                        "fact_decay_score", factDecayScore
                )
        );

        cachedResult = result;

        // 7. 检查是否需要告警
        if (result.needsAlert(alertThreshold)) {
            log.warn(result.generateAlertMessage());
        }

        log.info("[C层·衰减] 诊断完成: 衰减度={:.2f}, 等级={}, 保留约束={}, 丢失约束={}",
                totalDecayScore, decayLevel.getDisplayName(),
                retainedConstraints.size(), lostConstraints.size());

        return result;
    }

    /**
     * 计算约束衰减分数。
     */
    private double calculateConstraintDecayScore(List<String> retained, List<String> lost) {
        int total = retained.size() + lost.size();
        if (total == 0) return 0.0;
        return (double) lost.size() / total;
    }

    /**
     * 计算事实衰减分数。
     */
    private double calculateFactDecayScore(List<String> retained, List<String> lost) {
        int total = retained.size() + lost.size();
        if (total == 0) return 0.0;
        return (double) lost.size() / total;
    }

    /**
     * 生成建议措施。
     */
    private List<String> generateRecommendations(double decayScore,
                                                  List<String> lostConstraints,
                                                  List<String> lostFacts) {
        List<String> recommendations = new ArrayList<>();

        if (decayScore > criticalThreshold) {
            recommendations.add("⚠️ 严重衰减！建议立即重新注入初始目标和关键约束");
            recommendations.add("考虑重置对话或手动提醒 Agent 当前目标");
        } else if (decayScore > alertThreshold) {
            recommendations.add("⚠️ 高衰减！建议重新注入关键约束");
            if (!lostConstraints.isEmpty()) {
                recommendations.add("丢失的约束: " + String.join(", ", lostConstraints));
            }
        } else if (decayScore > 0.3) {
            recommendations.add("⚡ 中等衰减，建议在下一轮重新强调目标");
        } else if (decayScore > 0.0) {
            recommendations.add("ℹ️ 轻微衰减，继续监控");
        }

        if (!lostFacts.isEmpty()) {
            recommendations.add("关键事实可能丢失: " + String.join(", ", lostFacts));
        }

        return recommendations;
    }

    // ==================== 快捷方法 ====================

    /**
     * 获取当前衰减度（快速诊断）。
     *
     * @return 衰减度 [0.0-1.0]
     */
    public double getDecayScore() {
        if (cachedResult != null) {
            return cachedResult.decayScore();
        }
        return diagnose().decayScore();
    }

    /**
     * 获取最近的诊断结果。
     *
     * @return 诊断结果（无则返回 null）
     */
    public DecayDiagnosticResult getLatestResult() {
        return cachedResult;
    }

    /**
     * 检查是否需要告警。
     *
     * @return 如果需要告警，返回 true
     */
    public boolean needsAlert() {
        return getDecayScore() > alertThreshold;
    }

    /**
     * 检查是否需要重置。
     *
     * @return 如果需要重置，返回 true
     */
    public boolean needsReset() {
        return getDecayScore() > criticalThreshold;
    }

    // ==================== 统计与配置 ====================

    /**
     * 获取统计信息。
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("currentStep", currentStep.get());
        stats.put("probeCount", probeRegistry.size());
        stats.put("constraintCount", constraintTracker.size());
        stats.put("factCount", factTracker.size());
        stats.put("totalProbesInjected", totalProbesInjected.get());
        stats.put("totalResponsesRecorded", totalResponsesRecorded.get());
        stats.put("alertThreshold", alertThreshold);
        stats.put("criticalThreshold", criticalThreshold);

        if (cachedResult != null) {
            stats.put("latestDecayScore", cachedResult.decayScore());
            stats.put("latestDecayLevel", cachedResult.decayLevel().getDisplayName());
        }

        return stats;
    }

    /**
     * 重置诊断器（清空所有状态）。
     */
    public void reset() {
        currentStep.set(0);
        probeRegistry.clear();
        probeResponses.clear();
        constraintTracker.clear();
        factTracker.clear();
        cachedResult = null;
        totalProbesInjected.set(0);
        totalResponsesRecorded.set(0);

        log.info("[C层·衰减] 诊断器已重置");
    }

    // ==================== 配置方法 ====================

    public void setDefaultInjectionFrequency(int defaultInjectionFrequency) {
        this.defaultInjectionFrequency = defaultInjectionFrequency;
    }

    public void setAlertThreshold(double alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    public void setCriticalThreshold(double criticalThreshold) {
        this.criticalThreshold = criticalThreshold;
    }

    public void setResponseWindowSteps(int responseWindowSteps) {
        this.responseWindowSteps = responseWindowSteps;
    }

    // ==================== 内部类型 ====================

    /**
     * 探针响应记录。
     */
    private record ProbeResponse(
            String response,
            Instant timestamp,
            int step
    ) {}

    @Override
    public String toString() {
        return String.format("ContextDecayDiagnostic{step=%d, probes=%d, decay=%.2f}",
                currentStep.get(), probeRegistry.size(), getDecayScore());
    }
}