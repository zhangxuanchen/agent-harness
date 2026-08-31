package io.etclovg.codepilot.governance;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * G 层 · 影子流量采集器 — 治理规则有效性验证。
 *
 * <p>新规则上线前先在"影子模式"运行 1-2 周——不实际拦截，只记录"如果上线会拦截哪些请求"。
 * 通过 Precision/Recall/FPR 三指标量化治理有效性，使"治理有用了"从感觉变为数据证据。
 * 对应书中 Ch10 §10.6.2 — 治理规则的有效性验证。
 *
 * <h3>四指标体系</h3>
 * <ol>
 *   <li><b>Precision</b> = TP/(TP+FP)：拦截中真实危险操作占比，目标 &gt; 85%</li>
 *   <li><b>Recall</b> = TP/(TP+FN)：真实危险操作中被拦截占比，目标 &gt; 90%</li>
 *   <li><b>FPR</b> = FP/(FP+TN)：合法操作被误拦概率，目标 &lt; 5%</li>
 *   <li><b>Shadow Mode</b>：新规则先影子运行，达标后再真正启用</li>
 * </ol>
 *
 * <p><b>效果</b>：影子模式让新规则在不影响生产的前提下完成 Precision 验证；
 * 每月随机抽 100 条拦截样本人工审计，交叉验证自动化指标。
 */
@Component
public class ShadowTrafficCollector extends AbstractLayerMiddleware {

    /** 影子规则集：ruleId → 待验证的规则判定函数 */
    private final Map<String, ShadowRule> shadowRules = new ConcurrentHashMap<>();

    /** 影子判定记录：ruleId → 判定样本列表 */
    private final Map<String, List<ShadowVerdict>> verdictLog = new ConcurrentHashMap<>();

    /** 人工标注结果：ruleId → verdictId → 是否真实危险 */
    private final Map<String, Map<String, Boolean>> humanLabels = new ConcurrentHashMap<>();

    /** 是否处于影子模式（不实际拦截，只记录） */
    private volatile boolean shadowMode = true;

    public ShadowTrafficCollector() {
        super(Layer.G, "ShadowTrafficCollector");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!shadowMode) {
            // 非影子模式：规则已上线，正常放行（实际拦截由其他 G 层中间件负责）
            return next.apply(input);
        }

        // 影子模式：对待验证规则执行判定但不拦截，只记录"如果上线会怎样"
        Map<String, Object> context = rc.getExtra();
        String toolName = context.getOrDefault("tool.name", "").toString();
        String userInput = context.getOrDefault("user.input", "").toString();
        String sessionId = context.getOrDefault("session.id", "default").toString();

        for (Map.Entry<String, ShadowRule> entry : shadowRules.entrySet()) {
            String ruleId = entry.getKey();
            ShadowRule rule = entry.getValue();

            ShadowVerdict verdict = rule.evaluate(toolName, userInput, context);
            if (verdict.wouldBlock()) {
                // 影子拦截：记录但不真正拦截
                recordShadowVerdict(ruleId, verdict, sessionId);
                log.debug("[G层-影子] 规则 {} 在会话 {} 影子拦截（未实际拦截）: {}",
                        ruleId, sessionId, verdict.reason());
            }
        }

        return next.apply(input);
    }

    // ========== 影子规则管理 ==========

    /**
     * 注册待验证的影子规则——新规则先在影子模式跑 1-2 周。
     */
    public void registerShadowRule(String ruleId, ShadowRule rule) {
        shadowRules.put(ruleId, rule);
        verdictLog.put(ruleId, Collections.synchronizedList(new ArrayList<>()));
        log.info("[G层-影子] 影子规则已注册: {} (当前共 {} 条)", ruleId, shadowRules.size());
    }

    /**
     * 影子模式达标后，正式启用规则——切换为真实拦截。
     */
    public void promoteRule(String ruleId) {
        EffectivenessMetrics metrics = computeMetrics(ruleId);
        if (metrics.precision() >= 0.85 && metrics.fpr() <= 0.05) {
            shadowRules.remove(ruleId);
            log.info("[G层-影子] 规则 {} 已达标 (P={:.2f} FPR={:.2f})，提升为正式规则", ruleId,
                    metrics.precision(), metrics.fpr());
        } else {
            log.warn("[G层-影子] 规则 {} 未达标 (P={:.2f} FPR={:.2f})，拒绝提升", ruleId,
                    metrics.precision(), metrics.fpr());
        }
    }

    // ========== 有效性指标计算 ==========

    /**
     * 计算规则的四项有效性指标——基于影子判定 + 人工标注。
     *
     * @param ruleId 规则 ID
     * @return Precision/Recall/FPR 指标
     */
    public EffectivenessMetrics computeMetrics(String ruleId) {
        List<ShadowVerdict> verdicts = verdictLog.getOrDefault(ruleId, List.of());
        Map<String, Boolean> labels = humanLabels.getOrDefault(ruleId, Map.of());

        if (verdicts.isEmpty()) {
            // 无判定样本时指标为零，但仍报告已标注数（标注系统独立于判定日志）
            return new EffectivenessMetrics(0, 0, 0, 0, labels.size());
        }

        long tp = 0;  // 真阳性：影子拦截 + 人工确认真实危险
        long fp = 0;  // 假阳性：影子拦截 + 人工确认合法（误拦截）
        long fn = 0;  // 假阴性：影子放行 + 人工确认危险（漏拦截）
        long tn = 0;  // 真阴性：影子放行 + 人工确认合法

        for (ShadowVerdict v : verdicts) {
            Boolean realDangerous = labels.get(v.verdictId());
            if (realDangerous == null) continue;  // 未标注的样本跳过

            if (v.wouldBlock() && realDangerous) tp++;
            else if (v.wouldBlock() && !realDangerous) fp++;
            else if (!v.wouldBlock() && realDangerous) fn++;
            else tn++;
        }

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double fpr = (fp + tn) > 0 ? (double) fp / (fp + tn) : 0;
        long total = verdicts.size();

        return new EffectivenessMetrics(precision, recall, fpr, total, labels.size());
    }

    /**
     * 提交人工标注——每月随机抽 100 条拦截样本，由安全工程师逐条判断。
     */
    public void submitHumanLabel(String ruleId, String verdictId, boolean isRealDangerous) {
        humanLabels.computeIfAbsent(ruleId, k -> new ConcurrentHashMap<>())
                .put(verdictId, isRealDangerous);
    }

    /**
     * 随机抽取 N 条影子拦截样本供人工审计。
     */
    public List<ShadowVerdict> sampleForAudit(String ruleId, int sampleSize) {
        List<ShadowVerdict> verdicts = verdictLog.getOrDefault(ruleId, List.of());
        List<ShadowVerdict> blocked = verdicts.stream().filter(ShadowVerdict::wouldBlock).toList();

        if (blocked.size() <= sampleSize) return new ArrayList<>(blocked);

        // 简单随机抽样
        List<ShadowVerdict> copy = new ArrayList<>(blocked);
        Collections.shuffle(copy);
        return copy.subList(0, sampleSize);
    }

    // ========== 辅助方法 ==========

    private void recordShadowVerdict(String ruleId, ShadowVerdict verdict, String sessionId) {
        List<ShadowVerdict> log = verdictLog.computeIfAbsent(
                ruleId, k -> Collections.synchronizedList(new ArrayList<>()));
        log.add(verdict);

        // 限制日志大小
        if (log.size() > 10000) {
            synchronized (log) {
                if (log.size() > 10000) {
                    log.subList(0, 1000).clear();
                }
            }
        }
    }

    public void setShadowMode(boolean enabled) {
        this.shadowMode = enabled;
        log.info("[G层-影子] 影子模式: {}", enabled ? "启用（不实际拦截）" : "关闭（规则已上线）");
    }

    public boolean isShadowMode() {
        return shadowMode;
    }

    public Map<String, EffectivenessMetrics> getAllMetrics() {
        Map<String, EffectivenessMetrics> all = new LinkedHashMap<>();
        for (String ruleId : shadowRules.keySet()) {
            all.put(ruleId, computeMetrics(ruleId));
        }
        return all;
    }

    // ========== 数据记录 ==========

    /**
     * 影子规则——判定函数接口。
     */
    @FunctionalInterface
    public interface ShadowRule {
        ShadowVerdict evaluate(String toolName, String userInput, Map<String, Object> context);
    }

    /**
     * 影子判定结果——记录"如果上线会拦截还是放行"。
     */
    public record ShadowVerdict(
            String verdictId,
            boolean wouldBlock,
            String reason,
            String toolName,
            String sessionId,
            Instant timestamp
    ) {
        private static final AtomicLong COUNTER = new AtomicLong(0);

        public static ShadowVerdict of(boolean wouldBlock, String reason,
                                       String toolName, String sessionId) {
            return new ShadowVerdict(
                    "v-" + COUNTER.incrementAndGet(),
                    wouldBlock, reason, toolName, sessionId, Instant.now()
            );
        }
    }

    /**
     * 治理有效性四指标。
     */
    public record EffectivenessMetrics(
            double precision,   // TP/(TP+FP) 目标 > 0.85
            double recall,      // TP/(TP+FN) 目标 > 0.90
            double fpr,         // FP/(FP+TN) 目标 < 0.05
            long totalVerdicts,
            long labeledCount
    ) {
        public boolean meetsStandard() {
            return precision >= 0.85 && recall >= 0.90 && fpr <= 0.05;
        }

        public String summary() {
            return String.format("P=%.2f R=%.2f FPR=%.2f (样本=%d 标注=%d %s)",
                    precision, recall, fpr, totalVerdicts, labeledCount,
                    meetsStandard() ? "✅达标" : "❌未达标");
        }
    }

    /**
     * 简化版 AtomicLong（与 OutputGuardAdvisor 保持一致风格）。
     */
    static class AtomicLong {
        private long value;
        public AtomicLong(long v) { this.value = v; }
        public synchronized long incrementAndGet() { return ++value; }
    }
}
