package io.etclovg.codepilot.governance;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * G 层 · 会话检查点监控 — 异步分析完整会话轨迹与风险模式。
 *
 * <p>SessionMonitor 作为治理安全层的核心组件之一，负责追踪每个会话的完整生命周期，
 * 通过累计风险评分、越权访问检测、数据泄露风险识别和异常行为模式分析，
 * 为 G 层提供实时的会话级治理能力。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>风险分数追踪</b> — 为每个会话维护累计风险评分，超过阈值触发告警</li>
 *   <li><b>越权访问检测</b> — 识别多次尝试访问敏感工具的行为模式</li>
 *   <li><b>数据泄露检测</b> — 监控输出中敏感关键词的出现频率</li>
 *   <li><b>异常行为检测</b> — 分析工具调用序列，识别异常模式</li>
 *   <li><b>会话风险报告</b> — 提供会话级别的综合风险评估</li>
 * </ul>
 *
 * <p>对应书中 Ch10 §10.4 会话检查点。
 */
@Component
public class SessionMonitor extends AbstractLayerMiddleware {

    /**
     * 会话状态注册表：sessionId → SessionState
     * 使用 ConcurrentHashMap 保证多线程安全
     */
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * 会话模式注册表：sessionId → 检测到的异常模式列表
     */
    private final Map<String, List<DetectedPattern>> detectedPatterns = new ConcurrentHashMap<>();

    /**
     * 配置：敏感工具列表（访问需要特别授权）
     */
    private final List<String> sensitiveTools = new ArrayList<>(Arrays.asList(
            "file_delete", "file_write", "shell_exec", "database_drop",
            "user_manage", "permission_change", "credential_access"
    ));

    /**
     * 配置：敏感关键词列表（输出中出现即标记风险）
     */
    private final List<String> sensitiveKeywords = new ArrayList<>(Arrays.asList(
            "password", "secret_key", "api_key", "token", "credential",
            "内部机密", "confidential", "绝密", "top_secret"
    ));

    /**
     * 配置：越权检测阈值
     */
    private int unauthorizedAccessThreshold = 3;

    /**
     * 配置：数据泄露告警阈值（关键词出现次数）
     */
    private int dataLeakageThreshold = 2;

    /**
     * 配置：高风险分数阈值
     */
    private double highRiskThreshold = 5.0;

    /**
     * 配置：危险风险分数阈值
     */
    private double criticalRiskThreshold = 10.0;

    public SessionMonitor() {
        super(Layer.G, "SessionMonitor");
        log.info("[G层·会话监控] SessionMonitor 初始化完成");
    }

    /**
     * 在每次调用前后记录会话状态，
     * 并在调用后异步分析会话轨迹。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String sessionId = context.getOrDefault("session.id", "unknown").toString();
        String userId = context.getOrDefault("user.id", "anonymous").toString();
        String toolName = context.getOrDefault("tool.name", "").toString();

        // 初始化或获取会话状态
        SessionState state = sessionStates.computeIfAbsent(sessionId,
                k -> createInitialState(sessionId, userId));

        synchronized (state) {
            state.lastUpdatedAt = Instant.now();
        }

        // 记录工具调用
        if (!toolName.isBlank()) {
            recordToolCall(state, toolName);
        }

        // 执行调用链，完成后分析会话轨迹
        Instant startTime = Instant.now();
        return next.apply(input).doOnComplete(() -> {
            long latencyMs = Duration.between(startTime, Instant.now()).toMillis();

            // 分析响应中的敏感输出
            String output = extractOutput(rc);
            if (!output.isBlank()) {
                analyzeSensitiveOutput(state, output);
            }

            // 更新风险评分
            updateRiskScore(state, toolName, output, latencyMs);

            // 检测异常行为模式
            detectAnomalousPatterns(state, toolName);

            // 检查是否需要触发告警
            checkAndTriggerAlerts(state, sessionId);

            log.debug("[G层·会话监控] 会话 {} 调用处理完成: tool={} latency={}ms riskScore={}",
                    sessionId, toolName, latencyMs, state.riskScore);
        });
    }

    // ========== 会话状态管理 ==========

    /**
     * 创建初始会话状态
     */
    private SessionState createInitialState(String sessionId, String userId) {
        SessionState state = new SessionState();
        state.sessionId = sessionId;
        state.userId = userId;
        state.riskScore = 0.0;
        state.createdAt = Instant.now();
        state.lastUpdatedAt = Instant.now();
        state.toolCalls = new ArrayList<>();
        state.sensitiveOutputs = new ArrayList<>();
        log.info("[G层·会话监控] 新会话注册: sessionId={}, userId={}", sessionId, userId);
        return state;
    }

    /**
     * 记录工具调用到会话状态
     */
    private void recordToolCall(SessionState state, String toolName) {
        synchronized (state) {
            state.toolCalls.add(new ToolCallRecord(
                    toolName,
                    Instant.now(),
                    isSensitiveTool(toolName)
            ));

            // 限制工具调用记录数量，防止内存泄漏
            if (state.toolCalls.size() > 1000) {
                state.toolCalls.subList(0, state.toolCalls.size() - 1000).clear();
                log.warn("[G层·会话监控] 会话 {} 工具调用记录超限，已裁剪", state.sessionId);
            }
        }
    }

    /**
     * 分析输出中的敏感内容
     */
    private void analyzeSensitiveOutput(SessionState state, String output) {
        List<String> foundKeywords = new ArrayList<>();
        String lowerOutput = output.toLowerCase();

        for (String keyword : sensitiveKeywords) {
            if (lowerOutput.contains(keyword.toLowerCase())) {
                foundKeywords.add(keyword);
            }
        }

        if (!foundKeywords.isEmpty()) {
            synchronized (state) {
                state.sensitiveOutputs.add(new SensitiveOutputRecord(
                        Instant.now(),
                        foundKeywords,
                        truncate(output, 200)
                ));

                // 限制敏感输出记录数量
                if (state.sensitiveOutputs.size() > 500) {
                    state.sensitiveOutputs.subList(0, state.sensitiveOutputs.size() - 500).clear();
                }
            }

            log.warn("[G层·会话监控] 会话 {} 输出包含敏感关键词: {}",
                    state.sessionId, foundKeywords);
        }
    }

    /**
     * 更新会话风险评分
     */
    private void updateRiskScore(SessionState state, String toolName, String output, long latencyMs) {
        double scoreDelta = 0.0;

        // 敏感工具调用加分
        if (!toolName.isBlank() && isSensitiveTool(toolName)) {
            scoreDelta += 1.5;
            log.debug("[G层·会话监控] 会话 {} 调用敏感工具 {}: +1.5 风险分", state.sessionId, toolName);
        }

        // 敏感关键词输出加分
        String lowerOutput = output.toLowerCase();
        long keywordCount = sensitiveKeywords.stream()
                .filter(kw -> lowerOutput.contains(kw.toLowerCase()))
                .count();
        if (keywordCount > 0) {
            scoreDelta += keywordCount * 1.0;
        }

        // 高延迟异常加分（可能意味着异常行为）
        if (latencyMs > 30000) {
            scoreDelta += 0.5;
        }

        // 负反馈（调用成功且无敏感内容）
        if (scoreDelta == 0.0) {
            scoreDelta = -0.1;
        }

        synchronized (state) {
            state.riskScore = Math.max(0, state.riskScore + scoreDelta);
        }
    }

    /**
     * 检测异常行为模式
     */
    private void detectAnomalousPatterns(SessionState state, String currentTool) {
        List<ToolCallRecord> recentCalls;
        synchronized (state) {
            recentCalls = new ArrayList<>(state.toolCalls);
        }

        // 模式 1：短时间内多次调用敏感工具
        long recentSensitiveCount = recentCalls.stream()
                .filter(ToolCallRecord::sensitive)
                .count();
        if (recentSensitiveCount >= unauthorizedAccessThreshold) {
            DetectedPattern pattern = new DetectedPattern(
                    PatternType.UNAUTHORIZED_ACCESS,
                    "短时间内 " + recentSensitiveCount + " 次尝试访问敏感工具",
                    Severity.HIGH,
                    Instant.now()
            );
            addDetectedPattern(state.sessionId, pattern);
        }

        // 模式 2：频繁调用文件系统相关工具
        long fileOperationCount = recentCalls.stream()
                .filter(c -> c.toolName().startsWith("file_"))
                .count();
        if (fileOperationCount >= 5) {
            DetectedPattern pattern = new DetectedPattern(
                    PatternType.FILE_SYSTEM_OVERUSE,
                    "频繁文件系统操作: " + fileOperationCount + " 次",
                    Severity.MEDIUM,
                    Instant.now()
            );
            addDetectedPattern(state.sessionId, pattern);
        }

        // 模式 3：异常的工具调用序列
        if (currentTool != null && !currentTool.isBlank()) {
            detectAbnormalSequence(state, recentCalls);
        }

        // 模式 4：敏感输出频率过高
        int sensitiveOutputCount;
        synchronized (state) {
            sensitiveOutputCount = state.sensitiveOutputs.size();
        }
        if (sensitiveOutputCount >= dataLeakageThreshold) {
            DetectedPattern pattern = new DetectedPattern(
                    PatternType.DATA_LEAKAGE_RISK,
                    "检测到 " + sensitiveOutputCount + " 次敏感输出",
                    Severity.CRITICAL,
                    Instant.now()
            );
            addDetectedPattern(state.sessionId, pattern);
        }
    }

    /**
     * 检测异常的工具调用序列（如：先读文件再删文件）
     */
    private void detectAbnormalSequence(SessionState state, List<ToolCallRecord> recentCalls) {
        int size = recentCalls.size();
        if (size < 3) return;

        String lastTool = recentCalls.get(size - 1).toolName();
        String prevTool = recentCalls.get(size - 2).toolName();

        // 检测 read → write → delete 模式
        if (lastTool.contains("delete") && prevTool.contains("write")) {
            DetectedPattern pattern = new DetectedPattern(
                    PatternType.SUSPICIOUS_SEQUENCE,
                    "可疑操作序列: write → delete 模式",
                    Severity.HIGH,
                    Instant.now()
            );
            addDetectedPattern(state.sessionId, pattern);
        }

        // 检测 shell 执行模式
        if (lastTool.equals("shell_exec")) {
            long shellCount = recentCalls.stream()
                    .filter(c -> c.toolName().equals("shell_exec"))
                    .count();
            if (shellCount >= 2) {
                DetectedPattern pattern = new DetectedPattern(
                        PatternType.SUSPICIOUS_SEQUENCE,
                        "多次 shell 执行调用: " + shellCount + " 次",
                        Severity.HIGH,
                        Instant.now()
                );
                addDetectedPattern(state.sessionId, pattern);
            }
        }
    }

    /**
     * 添加检测到的模式
     */
    private void addDetectedPattern(String sessionId, DetectedPattern pattern) {
        List<DetectedPattern> patterns = detectedPatterns.computeIfAbsent(
                sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        patterns.add(pattern);

        if (patterns.size() > 200) {
            patterns.subList(0, patterns.size() - 200).clear();
        }

        log.warn("[G层·会话监控] 会话 {} 检测到异常模式: type={} severity={} detail={}",
                sessionId, pattern.type(), pattern.severity(), pattern.detail());
    }

    /**
     * 检查并触发告警
     */
    private void checkAndTriggerAlerts(SessionState state, String sessionId) {
        double riskScore;
        synchronized (state) {
            riskScore = state.riskScore;
        }

        if (riskScore >= criticalRiskThreshold) {
            log.error("[G层·会话监控] 🔴 CRITICAL 会话 {} 风险分数 {:.1f} 超过严重阈值 {:.1f}！",
                    sessionId, riskScore, criticalRiskThreshold);
            log.error("[G层·会话监控] 会话详情: userId={}, toolCalls={}, sensitiveOutputs={}",
                    state.userId, state.toolCalls.size(), state.sensitiveOutputs.size());
        } else if (riskScore >= highRiskThreshold) {
            log.warn("[G层·会话监控] 🟠 HIGH 会话 {} 风险分数 {:.1f} 超过高风险阈值 {:.1f}",
                    sessionId, riskScore, highRiskThreshold);
        }
    }

    // ========== 查询接口 ==========

    /**
     * 获取指定会话的状态
     */
    public SessionState getSessionState(String sessionId) {
        return sessionStates.get(sessionId);
    }

    /**
     * 获取指定会话的风险报告
     */
    public SessionRiskReport getSessionRiskReport(String sessionId) {
        SessionState state = sessionStates.get(sessionId);
        if (state == null) {
            return new SessionRiskReport(sessionId, 0.0, RiskLevel.UNKNOWN,
                    List.of(), List.of(), 0);
        }

        List<DetectedPattern> patterns = detectedPatterns.getOrDefault(sessionId, List.of());
        RiskLevel level = calculateRiskLevel(state.riskScore);

        int sensitiveOutputCount;
        synchronized (state) {
            sensitiveOutputCount = state.sensitiveOutputs.size();
        }

        return new SessionRiskReport(
                sessionId,
                state.riskScore,
                level,
                new ArrayList<>(patterns),
                state.toolCalls,
                sensitiveOutputCount
        );
    }

    /**
     * 获取所有活跃会话的风险概览
     */
    public List<SessionRiskReport> getAllSessionRiskReports() {
        return sessionStates.keySet().stream()
                .map(this::getSessionRiskReport)
                .sorted(Comparator.comparingDouble(SessionRiskReport::riskScore).reversed())
                .toList();
    }

    /**
     * 获取高风险会话列表
     */
    public List<SessionRiskReport> getHighRiskSessions() {
        return getAllSessionRiskReports().stream()
                .filter(r -> r.riskLevel() == RiskLevel.HIGH || r.riskLevel() == RiskLevel.CRITICAL)
                .toList();
    }

    /**
     * 获取当前活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessionStates.size();
    }

    /**
     * 结束会话并清理资源
     */
    public void endSession(String sessionId) {
        SessionState state = sessionStates.remove(sessionId);
        if (state != null) {
            double riskScore = state.riskScore;
            List<DetectedPattern> patterns = detectedPatterns.remove(sessionId);

            log.info("[G层·会话监控] 会话 {} 已结束: riskScore={}, patterns={}",
                    sessionId, riskScore, patterns != null ? patterns.size() : 0);
        }
    }

    // ========== 配置方法 ==========

    public void setUnauthorizedAccessThreshold(int threshold) {
        this.unauthorizedAccessThreshold = threshold;
    }

    public void setDataLeakageThreshold(int threshold) {
        this.dataLeakageThreshold = threshold;
    }

    public void setHighRiskThreshold(double threshold) {
        this.highRiskThreshold = threshold;
    }

    public void setCriticalRiskThreshold(double threshold) {
        this.criticalRiskThreshold = threshold;
    }

    // ========== 辅助方法 ==========

    private boolean isSensitiveTool(String toolName) {
        return sensitiveTools.stream()
                .anyMatch(t -> t.equalsIgnoreCase(toolName));
    }

    private String extractOutput(RuntimeContext rc) {
        try {
            Object output = rc.getExtra().get("model.last_response");
            return output == null ? "" : output.toString();
        } catch (Exception e) {
            log.warn("[G层·会话监控] 提取响应输出失败: {}", e.getMessage());
            return "";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }

    private RiskLevel calculateRiskLevel(double riskScore) {
        if (riskScore >= criticalRiskThreshold) return RiskLevel.CRITICAL;
        if (riskScore >= highRiskThreshold) return RiskLevel.HIGH;
        if (riskScore >= highRiskThreshold / 2) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    // ========== 数据模型 ==========

    /**
     * 会话状态：追踪单个会话的完整风险状态
     * 对应书中 Ch10 §10.4 会话检查点的数据模型
     */
    public static class SessionState {
        String sessionId;
        String userId;
        double riskScore;
        List<ToolCallRecord> toolCalls;
        List<SensitiveOutputRecord> sensitiveOutputs;
        Instant createdAt;
        Instant lastUpdatedAt;

        public String getSessionId() { return sessionId; }
        public String getUserId() { return userId; }
        public double getRiskScore() { return riskScore; }
        public List<ToolCallRecord> getToolCalls() { return toolCalls; }
        public List<SensitiveOutputRecord> getSensitiveOutputs() { return sensitiveOutputs; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    }

    /**
     * 工具调用记录
     */
    public record ToolCallRecord(String toolName, Instant timestamp, boolean sensitive) {}

    /**
     * 敏感输出记录
     */
    public record SensitiveOutputRecord(Instant timestamp, List<String> keywords, String snippet) {}

    /**
     * 检测到的异常行为模式
     */
    public record DetectedPattern(PatternType type, String detail, Severity severity, Instant detectedAt) {}

    /**
     * 会话风险报告
     */
    public record SessionRiskReport(String sessionId, double riskScore, RiskLevel riskLevel,
                                    List<DetectedPattern> detectedPatterns,
                                    List<ToolCallRecord> toolCalls, int sensitiveOutputCount) {

        public String summary() {
            return String.format("[%s] 会话 %s 风险分=%.1f 工具调用=%d 敏感输出=%d 异常模式=%d",
                    riskLevel, sessionId, riskScore, toolCalls.size(),
                    sensitiveOutputCount, detectedPatterns.size());
        }
    }

    /**
     * 异常行为模式类型枚举
     */
    public enum PatternType {
        UNAUTHORIZED_ACCESS,
        DATA_LEAKAGE_RISK,
        FILE_SYSTEM_OVERUSE,
        SUSPICIOUS_SEQUENCE,
        ANOMALOUS_TOOL_CALLS
    }

    /**
     * 严重程度枚举
     */
    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 风险等级枚举
     */
    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL, UNKNOWN
    }
}
