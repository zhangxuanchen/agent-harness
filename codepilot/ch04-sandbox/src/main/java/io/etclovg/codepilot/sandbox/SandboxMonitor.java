package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 沙箱运行时监控。
 * <p>对应书中 Ch04 §4.6.1 —— 沙箱安全事件与运行时行为监控。
 * <p>监控内容包括：
 * <ul>
 *   <li>资源使用监控（CPU、内存、磁盘）</li>
 *   <li>安全事件检测（越权访问、数据泄露、异常行为）</li>
 *   <li>性能指标收集（启动时间、执行时间、资源利用率）</li>
 *   <li>异常告警（资源超限、安全事件、实例异常）</li>
 * </ul>
 */
@Component
public class SandboxMonitor {

    private static final Logger log = LoggerFactory.getLogger(SandboxMonitor.class);

    /**
     * 资源使用快照。
     */
    public record ResourceSnapshot(
            String sandboxId,
            SandboxProfile profile,
            double cpuUsagePercent,
            long memoryUsedMb,
            long diskUsedMb,
            int activeProcesses,
            Instant timestamp
    ) {
        public boolean isCpuCritical(double limit) {
            return cpuUsagePercent > limit * 0.9;
        }
        public boolean isMemoryCritical(long limitMb) {
            return memoryUsedMb > limitMb * 0.9;
        }
        public boolean isDiskCritical(long limitMb) {
            return diskUsedMb > limitMb * 0.9;
        }
    }

    /**
     * 安全事件记录。
     */
    public record SecurityEvent(
            String id,
            String sandboxId,
            String eventType,
            String severity,
            String description,
            String taskId,
            Instant timestamp,
            Map<String, Object> context
    ) {
        public enum Severity {
            INFO,
            WARNING,
            ERROR,
            CRITICAL
        }
    }

    /**
     * 告警配置。
     */
    public record AlertConfig(
            double cpuThresholdPercent,
            long memoryThresholdMb,
            long diskThresholdMb,
            int maxProcesses,
            List<String> blockedPatterns,
            List<String> sensitiveKeywords
    ) {
        public static AlertConfig defaultConfig() {
            return new AlertConfig(80.0, 512L, 10240L, 50,
                    List.of("rm -rf /", "drop table", "format c:"),
                    List.of("password", "secret", "token", "api_key", "credential"));
        }
    }

    private final SandboxPool sandboxPool;
    private final Map<String, List<ResourceSnapshot>> resourceHistory = new ConcurrentHashMap<>();
    private final List<SecurityEvent> securityEvents = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicLong> metricCounters = new ConcurrentHashMap<>();
    private AlertConfig alertConfig;

    public SandboxMonitor(SandboxPool sandboxPool) {
        this.sandboxPool = sandboxPool;
        this.alertConfig = AlertConfig.defaultConfig();
    }

    /**
     * 更新告警配置。
     */
    public void updateAlertConfig(AlertConfig config) {
        this.alertConfig = config;
        log.info("更新告警配置: cpuThreshold={}%, memoryThreshold={}MB, diskThreshold={}MB",
                config.cpuThresholdPercent(), config.memoryThresholdMb(), config.diskThresholdMb());
    }

    /**
     * 记录资源使用快照。
     */
    public void recordResourceSnapshot(ResourceSnapshot snapshot) {
        log.info("[SandboxMonitor] 记录资源快照: sandboxId={}, profile={}, cpu={}%, memory={}MB, disk={}MB, processes={}",
                snapshot.sandboxId(), snapshot.profile().getDisplayName(),
                String.format("%.1f", snapshot.cpuUsagePercent()),
                snapshot.memoryUsedMb(), snapshot.diskUsedMb(), snapshot.activeProcesses());

        resourceHistory.computeIfAbsent(snapshot.sandboxId(), k -> {
            log.debug("[SandboxMonitor] 初始化资源历史记录: sandboxId={}", k);
            return Collections.synchronizedList(new ArrayList<>());
        }).add(snapshot);

        log.debug("[SandboxMonitor] 当前历史记录数: sandboxId={}, count={}",
                snapshot.sandboxId(), resourceHistory.get(snapshot.sandboxId()).size());

        // 检查是否超过阈值
        checkResourceThreshold(snapshot);
    }

    /**
     * 记录安全事件。
     */
    public void recordSecurityEvent(SecurityEvent event) {
        log.info("[SandboxMonitor] 记录安全事件: id={}, type={}, severity={}, sandboxId={}, taskId={}",
                event.id(), event.eventType(), event.severity(), event.sandboxId(), event.taskId());

        if (log.isDebugEnabled()) {
            log.debug("[SandboxMonitor] 安全事件详情: description={}, context={}", event.description(), event.context());
        }

        securityEvents.add(event);

        log.debug("[SandboxMonitor] 当前安全事件总数: {}", securityEvents.size());

        // 触发告警
        if (event.severity().equals(SecurityEvent.Severity.ERROR.name())
                || event.severity().equals(SecurityEvent.Severity.CRITICAL.name())) {
            log.warn("[SandboxMonitor] 触发告警: severity={}, type={}, description={}",
                    event.severity(), event.eventType(), event.description());
            triggerAlert(event);
        }
    }

    /**
     * 检测危险操作。
     */
    public boolean detectDangerousOperation(String sandboxId, String command, String taskId) {
        if (command == null || command.isBlank()) {
            log.debug("[SandboxMonitor] 跳过危险操作检测: sandboxId={}, command为空", sandboxId);
            return false;
        }

        log.debug("[SandboxMonitor] 开始检测危险操作: sandboxId={}, taskId={}, command={}", sandboxId, taskId, command);

        for (String pattern : alertConfig.blockedPatterns()) {
            if (command.toLowerCase().contains(pattern.toLowerCase())) {
                log.error("[SandboxMonitor] 检测到危险操作: sandboxId={}, taskId={}, command={}, pattern={}",
                        sandboxId, taskId, command, pattern);

                SecurityEvent event = new SecurityEvent(
                        "evt-" + UUID.randomUUID().toString().substring(0, 8),
                        sandboxId, "DANGEROUS_OPERATION",
                        SecurityEvent.Severity.CRITICAL.name(),
                        "检测到危险操作: " + pattern,
                        taskId, Instant.now(),
                        Map.of("command", command, "pattern", pattern)
                );
                recordSecurityEvent(event);
                return true;
            }
        }

        log.debug("[SandboxMonitor] 危险操作检测通过: sandboxId={}, taskId={}", sandboxId, taskId);
        return false;
    }

    /**
     * 检测敏感信息泄露。
     */
    public boolean detectSensitiveDataLeak(String sandboxId, String output, String taskId) {
        if (output == null || output.isBlank()) {
            log.debug("[SandboxMonitor] 跳过敏感信息检测: sandboxId={}, output为空", sandboxId);
            return false;
        }

        log.debug("[SandboxMonitor] 开始检测敏感信息: sandboxId={}, taskId={}, outputLength={}", sandboxId, taskId, output.length());

        // 简单的关键词检测
        for (String keyword : alertConfig.sensitiveKeywords()) {
            if (output.toLowerCase().contains(keyword.toLowerCase())) {
                log.warn("[SandboxMonitor] 检测到敏感信息泄露: sandboxId={}, taskId={}, keyword={}",
                        sandboxId, taskId, keyword);

                SecurityEvent event = new SecurityEvent(
                        "evt-" + UUID.randomUUID().toString().substring(0, 8),
                        sandboxId, "SENSITIVE_DATA_LEAK",
                        SecurityEvent.Severity.WARNING.name(),
                        "检测到可能的敏感信息: " + keyword,
                        taskId, Instant.now(),
                        Map.of("keyword", keyword, "outputPreview", output.substring(0, Math.min(100, output.length())))
                );
                recordSecurityEvent(event);
                return true;
            }
        }

        log.debug("[SandboxMonitor] 敏感信息检测通过: sandboxId={}, taskId={}", sandboxId, taskId);
        return false;
    }

    /**
     * 记录性能指标。
     */
    public void recordMetric(String name, long value) {
        metricCounters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(value);
    }

    /**
     * 获取指定沙箱的资源历史。
     */
    public List<ResourceSnapshot> getResourceHistory(String sandboxId) {
        return Collections.unmodifiableList(
                resourceHistory.getOrDefault(sandboxId, Collections.emptyList())
        );
    }

    /**
     * 获取指定沙箱的最近 N 条资源快照。
     */
    public List<ResourceSnapshot> getRecentResourceHistory(String sandboxId, int limit) {
        List<ResourceSnapshot> history = resourceHistory.getOrDefault(sandboxId, Collections.emptyList());
        int start = Math.max(0, history.size() - limit);
        return history.subList(start, history.size());
    }

    /**
     * 获取指定时间范围内的安全事件。
     */
    public List<SecurityEvent> getSecurityEvents(Instant since) {
        return securityEvents.stream()
                .filter(e -> e.timestamp().isAfter(since))
                .toList();
    }

    /**
     * 获取所有安全事件。
     */
    public List<SecurityEvent> getAllSecurityEvents() {
        return Collections.unmodifiableList(securityEvents);
    }

    /**
     * 获取指标统计。
     */
    public Map<String, Long> getMetricCounters() {
        Map<String, Long> result = new LinkedHashMap<>();
        metricCounters.forEach((key, value) -> result.put(key, value.get()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 生成健康检查报告。
     */
    public Map<String, Object> generateHealthReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        Instant now = Instant.now();
        Instant oneHourAgo = now.minusSeconds(3600);

        // 池状态
        report.put("poolStatus", sandboxPool.getPoolStatus());

        // 活跃实例数
        report.put("activeInstances", sandboxPool.getActiveInstances().size());

        // 最近安全事件数
        long recentEvents = securityEvents.stream()
                .filter(e -> e.timestamp().isAfter(oneHourAgo))
                .count();
        report.put("securityEventsLastHour", recentEvents);

        // 严重事件数
        long criticalEvents = securityEvents.stream()
                .filter(e -> e.severity().equals(SecurityEvent.Severity.CRITICAL.name()))
                .count();
        report.put("criticalEventsTotal", criticalEvents);

        // 资源历史记录数
        report.put("trackedSandboxes", resourceHistory.size());

        // 指标统计
        report.put("metrics", getMetricCounters());

        // 告警配置
        Map<String, Object> alertConfigMap = new LinkedHashMap<>();
        alertConfigMap.put("cpuThreshold", alertConfig.cpuThresholdPercent());
        alertConfigMap.put("memoryThreshold", alertConfig.memoryThresholdMb());
        alertConfigMap.put("diskThreshold", alertConfig.diskThresholdMb());
        report.put("alertConfig", alertConfigMap);

        report.put("generatedAt", now.toString());

        return report;
    }

    /**
     * 定时清理旧数据（每小时执行）。
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupOldData() {
        log.info("开始清理旧监控数据");

        Instant cutoff = Instant.now().minusSeconds(86400); // 24 小时前

        // 清理旧的资源历史
        resourceHistory.forEach((sandboxId, snapshots) -> {
            snapshots.removeIf(s -> s.timestamp().isBefore(cutoff));
        });

        // 清理旧的安全事件
        securityEvents.removeIf(e -> e.timestamp().isBefore(cutoff));

        int cleanedSnapshots = resourceHistory.values().stream().mapToInt(List::size).sum();
        int cleanedEvents = securityEvents.size();

        log.info("清理完成: snapshots={}, events={}", cleanedSnapshots, cleanedEvents);
    }

    private void checkResourceThreshold(ResourceSnapshot snapshot) {
        SandboxProfile profile = snapshot.profile();

        log.debug("[SandboxMonitor] 检查资源阈值: sandboxId={}, profile={}", snapshot.sandboxId(), profile.getDisplayName());

        // CPU 检查
        double cpuLimit = profile.getCpuCores() * 100; // 假设 100% 每核
        double cpuPercent = (snapshot.cpuUsagePercent() / cpuLimit) * 100;
        if (snapshot.isCpuCritical(cpuLimit)) {
            log.warn("[SandboxMonitor] CPU 使用率接近阈值: sandboxId={}, cpu={}%, limit={}%, usagePercent={}%",
                    snapshot.sandboxId(), snapshot.cpuUsagePercent(), cpuLimit, cpuPercent);

            recordSecurityEvent(new SecurityEvent(
                    "evt-" + UUID.randomUUID().toString().substring(0, 8),
                    snapshot.sandboxId(), "CPU_THRESHOLD_EXCEEDED",
                    SecurityEvent.Severity.WARNING.name(),
                    String.format("CPU 使用率 %.1f%% 接近限制 %.0f%%", snapshot.cpuUsagePercent(), cpuLimit),
                    null, Instant.now(),
                    Map.of("cpuUsage", snapshot.cpuUsagePercent(), "cpuLimit", cpuLimit)
            ));
        } else {
            log.debug("[SandboxMonitor] CPU 使用率正常: sandboxId={}, cpu={}%, limit={}",
                    snapshot.sandboxId(), snapshot.cpuUsagePercent(), cpuLimit);
        }

        // 内存检查
        long memoryPercent = (snapshot.memoryUsedMb() * 100) / profile.getMemoryMb();
        if (snapshot.isMemoryCritical(profile.getMemoryMb())) {
            log.error("[SandboxMonitor] 内存使用接近阈值: sandboxId={}, memory={}MB, limit={}MB, usagePercent={}%",
                    snapshot.sandboxId(), snapshot.memoryUsedMb(), profile.getMemoryMb(), memoryPercent);

            recordSecurityEvent(new SecurityEvent(
                    "evt-" + UUID.randomUUID().toString().substring(0, 8),
                    snapshot.sandboxId(), "MEMORY_THRESHOLD_EXCEEDED",
                    SecurityEvent.Severity.ERROR.name(),
                    String.format("内存使用 %dMB 接近限制 %dMB", snapshot.memoryUsedMb(), profile.getMemoryMb()),
                    null, Instant.now(),
                    Map.of("memoryUsed", snapshot.memoryUsedMb(), "memoryLimit", profile.getMemoryMb())
            ));
        } else {
            log.debug("[SandboxMonitor] 内存使用正常: sandboxId={}, memory={}MB, limit={}MB",
                    snapshot.sandboxId(), snapshot.memoryUsedMb(), profile.getMemoryMb());
        }

        // 磁盘检查
        long diskLimitMb = profile.getDiskLimitGb() * 1024L;
        long diskPercent = (snapshot.diskUsedMb() * 100) / diskLimitMb;
        if (snapshot.isDiskCritical(diskLimitMb)) {
            log.warn("[SandboxMonitor] 磁盘使用接近阈值: sandboxId={}, disk={}MB, limit={}MB, usagePercent={}%",
                    snapshot.sandboxId(), snapshot.diskUsedMb(), diskLimitMb, diskPercent);

            recordSecurityEvent(new SecurityEvent(
                    "evt-" + UUID.randomUUID().toString().substring(0, 8),
                    snapshot.sandboxId(), "DISK_THRESHOLD_EXCEEDED",
                    SecurityEvent.Severity.WARNING.name(),
                    String.format("磁盘使用 %dMB 接近限制 %dGB", snapshot.diskUsedMb(), profile.getDiskLimitGb()),
                    null, Instant.now(),
                    Map.of("diskUsed", snapshot.diskUsedMb(), "diskLimit", diskLimitMb)
            ));
        } else {
            log.debug("[SandboxMonitor] 磁盘使用正常: sandboxId={}, disk={}MB, limit={}MB",
                    snapshot.sandboxId(), snapshot.diskUsedMb(), diskLimitMb);
        }
    }

    private void triggerAlert(SecurityEvent event) {
        log.error("⚠️ 安全告警触发: [{}] {} - {}", event.severity(), event.eventType(), event.description());
        // 实际生产中这里会调用告警系统（如 PagerDuty、飞书、钉钉等）
        // 当前实现仅记录日志
    }
}
