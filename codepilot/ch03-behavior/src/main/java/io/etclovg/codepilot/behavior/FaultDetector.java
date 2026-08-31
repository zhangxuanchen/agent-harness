package io.etclovg.codepilot.behavior;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故障检测器。
 * <p>对应书中 Ch03 §3.1 —— 五类故障模式的实时检测逻辑。
 * <p>在每步决策后调用 {@link #detectAll} 检测幻觉调用、循环卡死、目标漂移、
 * 上下文腐烂、涌现行为五类故障模式，将 58% 的失败从"事后分析"变为"实时拦截"。
 */
@Component
public class FaultDetector {

    /**
     * 故障模式枚举——每种模式对应一种可观测的异常行为。
     */
    public enum FaultPattern {
        HALLUCINATED_CALL("幻觉调用",   "工具名不在注册表 / 参数类型不匹配",
            "T 层: 参数 Schema 校验 + 工具白名单"),
        LOOP_DEATH("循环卡死",   "相同工具+参数在 N 步内重复",
            "L 层: 步数上限 + 重复检测 + 策略切换"),
        GOAL_DRIFT("目标漂移",   "当前行动与原始目标语义相似度下降",
            "C 层: 每 N 步注入原始目标 + 漂移告警"),
        CONTEXT_DECAY("上下文腐烂", "早期关键信息在上下文中不可召回",
            "C 层: 上下文预算五区制 + 结构化压缩"),
        EMERGENT_BEHAVIOR("涌现行为", "设计者无法提前定义的异常行为",
            "O+G 层: 全量事件日志 + 行为审计");

        private final String cnName, observableSignal, defenseLayer;
        FaultPattern(String cn, String signal, String defense) {
            this.cnName = cn; this.observableSignal = signal; this.defenseLayer = defense;
        }
        public String getCnName() { return cnName; }
        public String getObservableSignal() { return observableSignal; }
        public String getDefenseLayer() { return defenseLayer; }
    }

    /**
     * 检测结果——与书中 DetectionResult record 对应。
     */
    public record DetectionResult(
        FaultPattern pattern,
        int step,
        String detail,
        Severity severity
    ) {}

    /** 工具调用记录——与书中 ToolCall record 对应 */
    public record ToolCall(String toolName, String args) {}

    /** 问题严重程度——与书中 Severity enum 对应 */
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    private final Set<String> registeredPatterns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 检测五种故障模式——在每步决策后调用。
     *
     * @param step         当前步骤编号
     * @param decision     当前步的决策内容
     * @param toolCalls    工具调用历史
     * @param context      当前上下文
     * @param originalTask 原始任务目标（用于漂移检测）
     * @return 检测到的所有故障列表（空列表表示无故障）
     */
    public List<DetectionResult> detectAll(
            int step, String decision,
            List<ToolCall> toolCalls, String context, String originalTask) {

        List<DetectionResult> results = new ArrayList<>();

        detectHallucinatedCall(step, decision).ifPresent(results::add);
        detectLoopDeath(step, toolCalls).ifPresent(results::add);
        detectGoalDrift(step, decision, originalTask).ifPresent(results::add);
        detectContextDecay(step, context, originalTask).ifPresent(results::add);
        detectEmergentBehavior(step, decision).ifPresent(results::add);

        return results;
    }

    /** 检测幻觉调用：工具名不在白名单中 */
    private Optional<DetectionResult> detectHallucinatedCall(int step, String decision) {
        Set<String> whitelist = Set.of(
            "read_file", "write_file", "search", "execute_code",
            "list_files", "git_status", "git_commit");

        Matcher m = Pattern.compile("(\\w+)\\(").matcher(decision);
        while (m.find()) {
            String tool = m.group(1);
            if (!whitelist.contains(tool) && Character.isLowerCase(tool.charAt(0))) {
                return Optional.of(new DetectionResult(
                    FaultPattern.HALLUCINATED_CALL, step,
                    "工具[" + tool + "]不在白名单中", Severity.HIGH));
            }
        }
        return Optional.empty();
    }

    /** 检测循环卡死：相同工具+参数在 3 步内重复 */
    private Optional<DetectionResult> detectLoopDeath(int step, List<ToolCall> calls) {
        if (calls.size() < 3) return Optional.empty();
        ToolCall last = calls.get(calls.size() - 1);
        long repeats = calls.stream()
            .filter(c -> c.toolName().equals(last.toolName()) &&
                         c.args().equals(last.args()))
            .count();
        if (repeats >= 3) {
            return Optional.of(new DetectionResult(
                FaultPattern.LOOP_DEATH, step,
                "相同工具+参数重复 " + repeats + " 次", Severity.MEDIUM));
        }
        return Optional.empty();
    }

    /** 检测目标漂移：语义相似度低于阈值 */
    private Optional<DetectionResult> detectGoalDrift(
            int step, String decision, String originalTask) {
        double similarity = computeSemanticSimilarity(decision, originalTask);
        if (step > 3 && similarity < 0.3) {
            return Optional.of(new DetectionResult(
                FaultPattern.GOAL_DRIFT, step,
                "与原始目标相似度仅 " + String.format("%.0f%%", similarity * 100),
                Severity.HIGH));
        }
        return Optional.empty();
    }

    /** 检测上下文腐烂：原始任务关键词在上下文中消失 */
    private Optional<DetectionResult> detectContextDecay(
            int step, String context, String originalTask) {
        Set<String> keywords = Set.of(originalTask.toLowerCase().split("\\s+"));
        long found = keywords.stream()
            .filter(k -> context.toLowerCase().contains(k))
            .count();
        double ratio = (double) found / keywords.size();
        if (step > 5 && ratio < 0.4) {
            return Optional.of(new DetectionResult(
                FaultPattern.CONTEXT_DECAY, step,
                "原始任务关键词保留率仅 " + String.format("%.0f%%", ratio * 100),
                Severity.MEDIUM));
        }
        return Optional.empty();
    }

    /** 检测涌现行为：异常关键词触发 */
    private Optional<DetectionResult> detectEmergentBehavior(int step, String decision) {
        Set<String> dangerSignals = Set.of("联网", "挖矿", "外部", "网络请求", "curl");
        for (String signal : dangerSignals) {
            if (decision.contains(signal)) {
                return Optional.of(new DetectionResult(
                    FaultPattern.EMERGENT_BEHAVIOR, step,
                    "检测到异常行为信号: " + signal, Severity.CRITICAL));
            }
        }
        return Optional.empty();
    }

    private double computeSemanticSimilarity(String a, String b) {
        Set<String> ta = Set.of(a.toLowerCase().split("\\s+"));
        Set<String> tb = Set.of(b.toLowerCase().split("\\s+"));
        long common = ta.stream().filter(tb::contains).count();
        return (double) common / Math.max(ta.size(), tb.size());
    }

    /**
     * 注册故障模式名称（保留旧 API 以兼容测试）。
     */
    public void registerPattern(String patternName) {
        registeredPatterns.add(patternName);
    }
}
