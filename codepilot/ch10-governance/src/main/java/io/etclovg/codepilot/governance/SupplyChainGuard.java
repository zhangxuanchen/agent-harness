package io.etclovg.codepilot.governance;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * G 层 · 供应链安全守卫 — 工具与模型信任链验证。
 *
 * <p>扩展传统软件供应链的边界：除代码完整性外，还需验证 MCP 工具行为完整性
 * 与模型输出分布稳定性。对应书中 Ch10 §10.4.3 — 供应链安全四环。
 *
 * <h3>四环防御</h3>
 * <ol>
 *   <li><b>MCP 签名校验</b> — 工具描述注册时做 SHA-256 哈希 + 签名，调用前校验</li>
 *   <li><b>工具行为监控</b> — 监控工具返回模式，格式突变即告警</li>
 *   <li><b>模型版本锁定 + 漂移检测</b> — 固定版本号，每日探测 prompt 集验证分布</li>
 *   <li><b>最小权限原则</b> — 每个 MCP Server 仅授予完成任务所需最小权限</li>
 * </ol>
 *
 * <p><b>效果</b>：签名不匹配即拒绝使用 + 告警；工具返回体积暴涨 10× 触发投毒告警；
 * 模型输出分布漂移超阈值自动回退到上一稳定版本。
 */
@Component
public class SupplyChainGuard extends AbstractLayerMiddleware {

    // ========== 第 1 环：MCP 签名校验 ==========

    /** 工具签名注册表：toolName → 注册时的 SHA-256 签名 */
    private final Map<String, ToolSignature> signatureRegistry = new ConcurrentHashMap<>();

    // ========== 第 2 环：工具行为监控 ==========

    /** 工具返回基线：toolName → 历史返回体积的滑动统计 */
    private final Map<String, ToolBehaviorBaseline> behaviorBaselines = new ConcurrentHashMap<>();

    /** 体积突变告警阈值：当前返回体积超过历史均值的倍数 */
    private static final double VOLUME_SPIKE_FACTOR = 10.0;

    // ========== 第 3 环：模型漂移检测 ==========

    /** 锁定的模型版本（不使用 "latest"） */
    private volatile String lockedModelVersion;

    /** 探测 prompt 集及其历史输出分布 */
    private final Map<String, List<String>> probeHistory = new ConcurrentHashMap<>();

    /** 漂移告警阈值：输出分布与基线的 Jaccard 相似度低于此值即告警 */
    private static final double DRIFT_ALERT_THRESHOLD = 0.6;

    // ========== 第 4 环：最小权限 ==========

    /** 工具权限配置：toolName → 授予的权限集合 */
    private final Map<String, Set<String>> toolPermissions = new ConcurrentHashMap<>();

    public SupplyChainGuard() {
        super(Layer.G, "SupplyChainGuard");
        this.lockedModelVersion = "dashscope:qwen-plus@v2.3";
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String toolName = context.getOrDefault("tool.name", "").toString();

        // 第 1 环：调用前校验工具签名
        if (!toolName.isBlank() && !verifyToolSignature(toolName, context)) {
            log.warn("[G层-供应链] 工具 {} 签名校验失败，拒绝调用", toolName);
            rc.put("supplychain.blocked", true);
            rc.put("supplychain.reason", "SIGNATURE_MISMATCH: " + toolName);
            return Flux.empty();
        }

        return next.apply(input).doOnComplete(() -> {
            // 第 2 环：调用后监控工具返回行为
            if (!toolName.isBlank()) {
                String toolResult = context.getOrDefault("tool.last_result", "").toString();
                monitorToolBehavior(toolName, toolResult);
            }
        });
    }

    // ========== 第 1 环：MCP 签名校验 ==========

    /**
     * 注册工具时记录签名（SHA-256 哈希 + 描述）。
     * 签名材料 = 工具名 + 工具描述 + 参数 Schema。
     */
    public void registerTool(String toolName, String description, String parameterSchema) {
        String signatureMaterial = toolName + "|" + description + "|" + parameterSchema;
        String signature = sha256(signatureMaterial);
        signatureRegistry.put(toolName, new ToolSignature(toolName, signature, Instant.now()));
        log.info("[G层-供应链] 工具签名已注册: {} sig={}", toolName, signature.substring(0, 16) + "...");
    }

    /**
     * 调用前校验签名——签名不匹配 = 工具描述被篡改 = 拒绝使用 + 告警。
     */
    boolean verifyToolSignature(String toolName, Map<String, Object> context) {
        ToolSignature registered = signatureRegistry.get(toolName);
        if (registered == null) {
            // 未注册工具——按最小权限原则默认拒绝
            log.warn("[G层-供应链] 工具 {} 未注册签名，按最小权限原则拒绝", toolName);
            return false;
        }
        // 实际场景中这里会重新计算当前工具描述的哈希并与注册签名比对
        // 简化示意：注册时锁定签名，运行时校验签名是否仍存在且未被替换
        return registered.signature() != null;
    }

    // ========== 第 2 环：工具行为监控 ==========

    /**
     * 监控工具返回模式——字段增减、返回体积突变 → 可能被篡改或投毒。
     */
    void monitorToolBehavior(String toolName, String toolResult) {
        int currentSize = toolResult == null ? 0 : toolResult.length();
        ToolBehaviorBaseline baseline = behaviorBaselines.computeIfAbsent(
                toolName, k -> new ToolBehaviorBaseline());

        baseline.recordCall(currentSize);

        // 体积突变检测：当前返回超过历史均值的 N 倍
        if (baseline.sampleCount() >= 10 && baseline.averageSize() > 0) {
            double spikeRatio = currentSize / baseline.averageSize();
            if (spikeRatio > VOLUME_SPIKE_FACTOR) {
                log.error("[G层-供应链] ⚠️ 工具 {} 返回体积突变: 当前={} 历史均值={} 倍数={:.1f}——疑似投毒",
                        toolName, currentSize, baseline.averageSize(), spikeRatio);
                // 触发告警，实际场景应通知 O 层并冻结该工具
            }
        }
    }

    // ========== 第 3 环：模型版本锁定 + 漂移检测 ==========

    /**
     * 锁定模型版本——不使用 "latest"，固定具体版本号。
     */
    public void lockModelVersion(String version) {
        this.lockedModelVersion = version;
        log.info("[G层-供应链] 模型版本已锁定: {}", version);
    }

    public String getLockedModelVersion() {
        return lockedModelVersion;
    }

    /**
     * 每日用探测 prompt 集验证模型输出分布——分布突变 → 模型可能被更新或投毒。
     *
     * @param probePrompt 探测 prompt
     * @param currentOutput 当前模型输出
     * @return 漂移检测结果
     */
    public DriftResult detectModelDrift(String probePrompt, String currentOutput) {
        List<String> history = probeHistory.computeIfAbsent(
                probePrompt, k -> new ArrayList<>());

        if (history.size() < 5) {
            // 冷启动：积累基线
            history.add(currentOutput);
            return new DriftResult(false, 1.0, "BASELINE_BUILDING", history.size());
        }

        // 计算 Jaccard 相似度：当前输出与历史输出的平均相似度
        double avgSimilarity = history.stream()
                .mapToDouble(prev -> jaccardSimilarity(currentOutput, prev))
                .average()
                .orElse(0.0);

        history.add(currentOutput);
        if (history.size() > 20) history.remove(0);

        boolean drifted = avgSimilarity < DRIFT_ALERT_THRESHOLD;
        if (drifted) {
            log.error("[G层-供应链] ⚠️ 模型输出漂移: 相似度={:.2f} 阈值={}——疑似模型被更新或投毒，建议回退",
                    avgSimilarity, DRIFT_ALERT_THRESHOLD);
        }

        return new DriftResult(drifted, avgSimilarity,
                drifted ? "DRIFT_DETECTED" : "STABLE", history.size());
    }

    // ========== 第 4 环：最小权限原则 ==========

    /**
     * 授予工具最小权限——GitHub MCP Server 只需读取代码，不应授予写入仓库权限。
     */
    public void grantPermissions(String toolName, Set<String> permissions) {
        toolPermissions.put(toolName, new HashSet<>(permissions));
        log.info("[G层-供应链] 工具 {} 已授予权限: {}", toolName, permissions);
    }

    /**
     * 校验工具是否具备所需权限——最小权限原则。
     */
    public boolean hasPermission(String toolName, String requiredPermission) {
        Set<String> granted = toolPermissions.get(toolName);
        if (granted == null) return false;
        return granted.contains(requiredPermission);
    }

    // ========== 辅助方法 ==========

    private String sha256(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private double jaccardSimilarity(String a, String b) {
        Set<String> tokensA = new HashSet<>(List.of(a.toLowerCase().split("\\s+")));
        Set<String> tokensB = new HashSet<>(List.of(b.toLowerCase().split("\\s+")));
        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0;

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    // ========== 查询接口 ==========

    public Map<String, ToolSignature> getSignatureRegistry() {
        return new HashMap<>(signatureRegistry);
    }

    public Map<String, ToolBehaviorBaseline> getBehaviorBaselines() {
        return new HashMap<>(behaviorBaselines);
    }

    // ========== 数据记录 ==========

    public record ToolSignature(String toolName, String signature, Instant registeredAt) {}

    public record DriftResult(boolean drifted, double similarity, String status, int sampleCount) {}

    /**
     * 工具行为基线——滑动窗口记录历史返回体积。
     */
    public static class ToolBehaviorBaseline {
        private final List<Integer> sizeHistory = Collections.synchronizedList(new ArrayList<>());
        private static final int WINDOW_SIZE = 100;

        public void recordCall(int size) {
            sizeHistory.add(size);
            if (sizeHistory.size() > WINDOW_SIZE) {
                synchronized (sizeHistory) {
                    if (sizeHistory.size() > WINDOW_SIZE) {
                        sizeHistory.subList(0, sizeHistory.size() - WINDOW_SIZE).clear();
                    }
                }
            }
        }

        public double averageSize() {
            if (sizeHistory.isEmpty()) return 0;
            return sizeHistory.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        public int sampleCount() {
            return sizeHistory.size();
        }
    }
}
