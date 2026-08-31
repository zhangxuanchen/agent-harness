package io.etclovg.codepilot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 动态工具注册表。
 * <p>对应书中 Ch05 §5.4 —— 工具的发现与注册机制。
 * <p>封装 MCP Client 连接管理和定时刷新逻辑：
 * <ul>
 *   <li><b>静态注册</b>：Spring Bean 自动注册本地工具</li>
 *   <li><b>动态发现</b>：定时扫描 MCP Server 发现远程工具</li>
 *   <li><b>健康检查</b>：定期检查工具可用性，标记不可用工具</li>
 *   <li><b>热更新</b>：工具增删改无需重启 Agent</li>
 * </ul>
 */
@Component
public class DynamicToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolRegistry.class);

    /**
     * 工具定义。
     */
    public record ToolDescriptor(
            String toolId,
            String toolName,
            String description,
            String version,
            ToolSource source,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            boolean available,
            Instant registeredAt,
            Instant lastCheckedAt,
            int errorCount
    ) {
        public enum ToolSource {
            LOCAL,      // 本地 Spring Bean
            MCP_REMOTE, // MCP 远程服务
            REGISTRY    // 中央工具注册中心
        }

        public ToolDescriptor withAvailability(boolean available) {
            return new ToolDescriptor(toolId(), toolName(), description(), version(),
                    source(), inputSchema(), outputSchema(), available,
                    registeredAt(), Instant.now(), available ? 0 : errorCount() + 1);
        }

        public boolean isHealthy() {
            return available && errorCount < 3;
        }
    }

    /**
     * 注册请求。
     */
    public record RegisterRequest(
            String toolId,
            String toolName,
            String description,
            String version,
            ToolDescriptor.ToolSource source,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema
    ) {}

    /**
     * 注册结果。
     */
    public record RegisterResult(
            String toolId,
            boolean success,
            String message,
            boolean isNewRegistration
    ) {}

    /**
     * 注册状态统计。
     */
    public record RegistryStats(
            int totalTools,
            int localTools,
            int remoteTools,
            int healthyTools,
            int degradedTools,
            int unavailableTools,
            long lastRefreshAt,
            long nextRefreshAt
    ) {}

    /** 工具 ID → 描述符 */
    private final Map<String, ToolDescriptor> registry = new ConcurrentHashMap<>();

    /** 工具名称 → 工具 ID 映射（支持模糊查找） */
    private final Map<String, String> nameIndex = new ConcurrentHashMap<>();

    /** MCP Server 连接配置 */
    private final List<McpServerConfig> mcpServers = Collections.synchronizedList(new ArrayList<>());

    /** 定时刷新调度器 */
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> refreshFuture;
    private ScheduledFuture<?> healthCheckFuture;

    /** 配置项 */
    private long refreshIntervalMs = 30_000L;      // 30 秒刷新
    private long healthCheckIntervalMs = 10_000L;  // 10 秒健康检查
    private int maxErrorCount = 5;                  // 最大错误次数
    private boolean autoRefreshEnabled = true;
    private boolean autoHealthCheckEnabled = true;

    /** 最近一次刷新时间 */
    private volatile long lastRefreshAt;

    /**
     * MCP Server 配置。
     */
    public record McpServerConfig(
            String serverId,
            String url,
            String name,
            long lastSyncAt,
            boolean connected
    ) {}

    public DynamicToolRegistry() {
        log.info("[ToolRegistry] ========== 初始化动态工具注册表 ==========");
        log.info("[ToolRegistry] 配置参数: refreshInterval={}ms, healthCheckInterval={}ms, maxErrorCount={}",
                refreshIntervalMs, healthCheckIntervalMs, maxErrorCount);
        log.info("[ToolRegistry] ========== 注册表初始化完成 ==========");
    }

    /**
     * 启动后台定时任务。
     */
    public void startBackgroundTasks() {
        log.info("[ToolRegistry] ========== 启动后台任务 ==========");

        if (scheduler != null && !scheduler.isShutdown()) {
            log.warn("[ToolRegistry] 后台任务已在运行，跳过重复启动");
            return;
        }

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "tool-registry-scheduler");
            t.setDaemon(true);
            return t;
        });

        // 定时刷新
        if (autoRefreshEnabled) {
            refreshFuture = scheduler.scheduleAtFixedRate(
                    this::refreshFromMcpServers,
                    refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS
            );
            log.info("[ToolRegistry] 已启动定时刷新任务: interval={}ms", refreshIntervalMs);
        }

        // 健康检查
        if (autoHealthCheckEnabled) {
            healthCheckFuture = scheduler.scheduleAtFixedRate(
                    this::runHealthCheck,
                    healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS
            );
            log.info("[ToolRegistry] 已启动定时健康检查任务: interval={}ms", healthCheckIntervalMs);
        }

        log.info("[ToolRegistry] ========== 后台任务启动完成 ==========");
    }

    /**
     * 停止后台定时任务。
     */
    public void stopBackgroundTasks() {
        log.info("[ToolRegistry] ========== 停止后台任务 ==========");

        if (refreshFuture != null) {
            refreshFuture.cancel(false);
            log.info("[ToolRegistry] 已取消定时刷新任务");
        }
        if (healthCheckFuture != null) {
            healthCheckFuture.cancel(false);
            log.info("[ToolRegistry] 已取消定时健康检查任务");
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            log.info("[ToolRegistry] 调度器已关闭");
        }

        log.info("[ToolRegistry] ========== 后台任务已停止 ==========");
    }

    /**
     * 注册工具。
     */
    public RegisterResult register(RegisterRequest request) {
        log.info("[ToolRegistry] ========== 开始注册工具 ==========");
        log.info("[ToolRegistry] 注册请求: toolId={}, toolName={}, source={}, version={}",
                request.toolId(), request.toolName(), request.source(), request.version());

        // 参数校验
        if (request.toolId() == null || request.toolId().isBlank()) {
            log.error("[ToolRegistry] 注册失败: toolId 为空");
            return new RegisterResult(null, false, "toolId 不能为空", false);
        }
        if (request.toolName() == null || request.toolName().isBlank()) {
            log.error("[ToolRegistry] 注册失败: toolName 为空, toolId={}", request.toolId());
            return new RegisterResult(request.toolId(), false, "toolName 不能为空", false);
        }

        ToolDescriptor existing = registry.get(request.toolId());
        boolean isNew = existing == null;

        if (isNew) {
            // 新工具注册
            ToolDescriptor descriptor = new ToolDescriptor(
                    request.toolId(), request.toolName(),
                    request.description(), request.version(),
                    request.source(), request.inputSchema(), request.outputSchema(),
                    true, Instant.now(), Instant.now(), 0
            );
            registry.put(request.toolId(), descriptor);
            nameIndex.put(request.toolName().toLowerCase(), request.toolId());

            log.info("[ToolRegistry] ========== 工具注册成功 ==========");
            log.info("[ToolRegistry] 注册详情: toolId={}, toolName={}, source={}, version={}, totalTools={}",
                    request.toolId(), request.toolName(), request.source(), request.version(), registry.size());
        } else {
            // 更新已存在的工具
            if (existing.source() != request.source()) {
                log.warn("[ToolRegistry] 工具来源变更: toolId={}, oldSource={}, newSource={}",
                        request.toolId(), existing.source(), request.source());
            }

            ToolDescriptor updated = new ToolDescriptor(
                    request.toolId(), request.toolName(),
                    request.description(), request.version(),
                    request.source(), request.inputSchema(), request.outputSchema(),
                    true, existing.registeredAt(), Instant.now(), existing.errorCount()
            );
            registry.put(request.toolId(), updated);
            nameIndex.put(request.toolName().toLowerCase(), request.toolId());

            log.info("[ToolRegistry] ========== 工具更新成功 ==========");
            log.info("[ToolRegistry] 更新详情: toolId={}, toolName={}, oldVersion={}, newVersion={}",
                    request.toolId(), request.toolName(), existing.version(), request.version());
        }

        return new RegisterResult(request.toolId(), true,
                isNew ? "新工具注册成功" : "工具更新成功", isNew);
    }

    /**
     * 批量注册工具。
     */
    public List<RegisterResult> registerAll(List<RegisterRequest> requests) {
        log.info("[ToolRegistry] ========== 批量注册工具 ==========");
        log.info("[ToolRegistry] 批量注册数量: count={}", requests.size());

        List<RegisterResult> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (RegisterRequest request : requests) {
            try {
                RegisterResult result = register(request);
                results.add(result);
                if (result.success()) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.error("[ToolRegistry] 批量注册异常: toolId={}, error={}",
                        request.toolId(), e.getMessage(), e);
                results.add(new RegisterResult(request.toolId(), false,
                        "注册异常: " + e.getMessage(), false));
                failCount++;
            }
        }

        log.info("[ToolRegistry] ========== 批量注册完成 ==========");
        log.info("[ToolRegistry] 结果统计: total={}, success={}, fail={}",
                requests.size(), successCount, failCount);

        return results;
    }

    /**
     * 注销工具。
     */
    public boolean unregister(String toolId) {
        log.info("[ToolRegistry] 准备注销工具: toolId={}", toolId);

        ToolDescriptor removed = registry.remove(toolId);
        if (removed == null) {
            log.warn("[ToolRegistry] 注销失败: 工具不存在, toolId={}", toolId);
            return false;
        }

        // 清理名称索引
        nameIndex.remove(removed.toolName().toLowerCase());

        log.info("[ToolRegistry] ========== 工具注销成功 ==========");
        log.info("[ToolRegistry] 注销详情: toolId={}, toolName={}, source={}, remainingTools={}",
                toolId, removed.toolName(), removed.source(), registry.size());

        return true;
    }

    /**
     * 根据 ID 获取工具。
     */
    public Optional<ToolDescriptor> getTool(String toolId) {
        Optional<ToolDescriptor> result = Optional.ofNullable(registry.get(toolId));
        if (result.isPresent()) {
            log.debug("[ToolRegistry] 获取工具成功: toolId={}, toolName={}, available={}",
                    toolId, result.get().toolName(), result.get().available());
        } else {
            log.warn("[ToolRegistry] 工具不存在: toolId={}, availableIds={}", toolId, registry.keySet());
        }
        return result;
    }

    /**
     * 根据名称查找工具。
     */
    public Optional<ToolDescriptor> findByName(String toolName) {
        String toolId = nameIndex.get(toolName.toLowerCase());
        if (toolId != null) {
            return getTool(toolId);
        }
        log.warn("[ToolRegistry] 按名称查找失败: toolName={}", toolName);
        return Optional.empty();
    }

    /**
     * 获取所有可用工具。
     */
    public List<ToolDescriptor> listAvailable() {
        List<ToolDescriptor> available = registry.values().stream()
                .filter(ToolDescriptor::available)
                .toList();
        log.debug("[ToolRegistry] 列出可用工具: total={}, available={}", registry.size(), available.size());
        return available;
    }

    /**
     * 按来源获取工具列表。
     */
    public List<ToolDescriptor> listBySource(ToolDescriptor.ToolSource source) {
        return registry.values().stream()
                .filter(t -> t.source() == source)
                .toList();
    }

    /**
     * 运行健康检查。
     */
    public void runHealthCheck() {
        log.debug("[ToolRegistry] ========== 开始健康检查 ==========");

        int healthy = 0;
        int degraded = 0;
        int unavailable = 0;

        for (Map.Entry<String, ToolDescriptor> entry : registry.entrySet()) {
            ToolDescriptor tool = entry.getValue();
            boolean wasHealthy = tool.isHealthy();

            // 模拟健康检查（实际应调用 tool.ping() 或类似方法）
            boolean nowHealthy = checkToolHealth(tool);

            if (!nowHealthy && wasHealthy) {
                log.warn("[ToolRegistry] 工具健康状态变更: toolId={}, toolName={}, status=HEALTHY->UNHEALTHY, errorCount={}",
                        tool.toolId(), tool.toolName(), tool.errorCount() + 1);
            }

            if (nowHealthy) {
                healthy++;
            } else if (tool.errorCount() < maxErrorCount) {
                degraded++;
                if (!wasHealthy && tool.errorCount() > 0) {
                    log.warn("[ToolRegistry] 工具降级: toolId={}, toolName={}, errorCount={}/{}",
                            tool.toolId(), tool.toolName(), tool.errorCount(), maxErrorCount);
                }
            } else {
                unavailable++;
                if (wasHealthy || tool.errorCount() == maxErrorCount) {
                    log.error("[ToolRegistry] 工具不可用: toolId={}, toolName={}, errorCount={}/{}",
                            tool.toolId(), tool.toolName(), tool.errorCount(), maxErrorCount);
                }
            }

            // 更新工具状态
            if (!nowHealthy) {
                registry.put(entry.getKey(), tool.withAvailability(false));
            } else if (!tool.available()) {
                registry.put(entry.getKey(), tool.withAvailability(true));
                log.info("[ToolRegistry] 工具恢复可用: toolId={}, toolName={}", tool.toolId(), tool.toolName());
            }
        }

        log.debug("[ToolRegistry] ========== 健康检查完成 ==========");
        log.debug("[ToolRegistry] 健康结果: total={}, healthy={}, degraded={}, unavailable={}",
                registry.size(), healthy, degraded, unavailable);
    }

    /**
     * 从 MCP Server 刷新工具列表。
     */
    public void refreshFromMcpServers() {
        log.debug("[ToolRegistry] ========== 开始 MCP 工具刷新 ==========");
        log.debug("[ToolRegistry] MCP Server 配置数: count={}", mcpServers.size());

        if (mcpServers.isEmpty()) {
            log.debug("[ToolRegistry] 无 MCP Server 配置，跳过刷新");
            return;
        }

        int totalDiscovered = 0;
        int totalErrors = 0;

        for (McpServerConfig server : mcpServers) {
            log.debug("[ToolRegistry] 连接 MCP Server: serverId={}, url={}", server.serverId(), server.url());

            try {
                // 模拟从 MCP Server 获取工具列表
                List<RegisterRequest> discovered = discoverToolsFromServer(server);

                if (!discovered.isEmpty()) {
                    log.debug("[ToolRegistry] MCP Server 发现工具: serverId={}, count={}",
                            server.serverId(), discovered.size());

                    List<RegisterResult> results = registerAll(discovered);
                    long newCount = results.stream().filter(RegisterResult::isNewRegistration).count();
                    totalDiscovered += discovered.size();

                    log.debug("[ToolRegistry] 工具注册完成: serverId={}, discovered={}, new={}, updated={}",
                            server.serverId(), discovered.size(), newCount, discovered.size() - newCount);
                }
            } catch (Exception e) {
                log.error("[ToolRegistry] MCP Server 刷新失败: serverId={}, url={}, error={}",
                        server.serverId(), server.url(), e.getMessage(), e);
                totalErrors++;
            }
        }

        lastRefreshAt = System.currentTimeMillis();

        log.info("[ToolRegistry] ========== MCP 工具刷新完成 ==========");
        log.info("[ToolRegistry] 刷新统计: servers={}, discovered={}, errors={}, totalTools={}",
                mcpServers.size(), totalDiscovered, totalErrors, registry.size());
    }

    /**
     * 添加 MCP Server 配置。
     */
    public void addMcpServer(McpServerConfig config) {
        log.info("[ToolRegistry] 添加 MCP Server: serverId={}, url={}, name={}",
                config.serverId(), config.url(), config.name());

        boolean exists = mcpServers.stream()
                .anyMatch(s -> s.serverId().equals(config.serverId()));

        if (exists) {
            log.warn("[ToolRegistry] MCP Server 已存在，将被覆盖: serverId={}", config.serverId());
            mcpServers.removeIf(s -> s.serverId().equals(config.serverId()));
        }

        mcpServers.add(config);

        log.info("[ToolRegistry] MCP Server 添加完成: serverId={}, totalServers={}",
                config.serverId(), mcpServers.size());
    }

    /**
     * 移除 MCP Server 配置。
     */
    public boolean removeMcpServer(String serverId) {
        log.info("[ToolRegistry] 准备移除 MCP Server: serverId={}", serverId);

        boolean removed = mcpServers.removeIf(s -> s.serverId().equals(serverId));
        if (removed) {
            // 移除该 Server 来源的工具
            int toolsRemoved = 0;
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, ToolDescriptor> entry : registry.entrySet()) {
                if (entry.getValue().source() == ToolDescriptor.ToolSource.MCP_REMOTE) {
                    toRemove.add(entry.getKey());
                }
            }
            for (String toolId : toRemove) {
                unregister(toolId);
                toolsRemoved++;
            }

            log.info("[ToolRegistry] MCP Server 已移除: serverId={}, toolsRemoved={}, remainingServers={}",
                    serverId, toolsRemoved, mcpServers.size());
        } else {
            log.warn("[ToolRegistry] MCP Server 不存在: serverId={}", serverId);
        }

        return removed;
    }

    /**
     * 获取注册表统计信息。
     */
    public RegistryStats getStats() {
        int localCount = 0;
        int remoteCount = 0;
        int healthyCount = 0;
        int degradedCount = 0;
        int unavailableCount = 0;

        for (ToolDescriptor tool : registry.values()) {
            if (tool.source() == ToolDescriptor.ToolSource.LOCAL) {
                localCount++;
            } else {
                remoteCount++;
            }

            if (tool.isHealthy()) {
                healthyCount++;
            } else if (tool.errorCount() < maxErrorCount) {
                degradedCount++;
            } else {
                unavailableCount++;
            }
        }

        long nextRefresh = autoRefreshEnabled
                ? lastRefreshAt + refreshIntervalMs
                : 0;

        return new RegistryStats(
                registry.size(), localCount, remoteCount,
                healthyCount, degradedCount, unavailableCount,
                lastRefreshAt, nextRefresh
        );
    }

    /**
     * 获取所有工具描述。
     */
    public Collection<ToolDescriptor> getAllTools() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * 清除所有工具（测试用）。
     */
    public void clear() {
        log.info("[ToolRegistry] 清除所有工具: beforeCount={}", registry.size());
        registry.clear();
        nameIndex.clear();
        log.info("[ToolRegistry] 清除完成: afterCount={}", registry.size());
    }

    // ---- 内部方法 ----

    private boolean checkToolHealth(ToolDescriptor tool) {
        // 简单健康检查逻辑：检查是否超过最大错误次数
        // 实际生产环境应调用 tool.execute() 发送心跳
        return tool.errorCount() < maxErrorCount;
    }

    private List<RegisterRequest> discoverToolsFromServer(McpServerConfig server) {
        // 实际实现应调用 MCP Client 的 listTools() 方法
        // 这里返回空列表作为占位
        log.debug("[ToolRegistry] 模拟 MCP 工具发现: serverId={}", server.serverId());
        return Collections.emptyList();
    }

    // ---- 配置 Getter/Setter ----

    public long getRefreshIntervalMs() { return refreshIntervalMs; }
    public void setRefreshIntervalMs(long ms) {
        this.refreshIntervalMs = ms;
        log.info("[ToolRegistry] 刷新间隔已更新: old={}ms, new={}ms", refreshIntervalMs, ms);
    }

    public long getHealthCheckIntervalMs() { return healthCheckIntervalMs; }
    public void setHealthCheckIntervalMs(long ms) {
        this.healthCheckIntervalMs = ms;
        log.info("[ToolRegistry] 健康检查间隔已更新: old={}ms, new={}ms", healthCheckIntervalMs, ms);
    }

    public int getMaxErrorCount() { return maxErrorCount; }
    public void setMaxErrorCount(int count) {
        this.maxErrorCount = count;
        log.info("[ToolRegistry] 最大错误次数已更新: old={}, new={}", maxErrorCount, count);
    }

    public boolean isAutoRefreshEnabled() { return autoRefreshEnabled; }
    public void setAutoRefreshEnabled(boolean enabled) {
        this.autoRefreshEnabled = enabled;
        log.info("[ToolRegistry] 自动刷新已{}: enabled={}", enabled ? "启用" : "禁用", enabled);
    }

    public boolean isAutoHealthCheckEnabled() { return autoHealthCheckEnabled; }
    public void setAutoHealthCheckEnabled(boolean enabled) {
        this.autoHealthCheckEnabled = enabled;
        log.info("[ToolRegistry] 自动健康检查已{}: enabled={}", enabled ? "启用" : "禁用", enabled);
    }
}
