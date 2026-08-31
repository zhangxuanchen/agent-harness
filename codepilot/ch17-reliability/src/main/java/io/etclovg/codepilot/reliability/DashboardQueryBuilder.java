package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 生产监控层 · 三层仪表盘查询构建器。
 *
 * <p>DashboardQueryBuilder 提供了面向三个层级的仪表盘查询能力，
 * 支持链式调用 API，便于构建复杂的查询逻辑：
 *
 * <ul>
 *   <li><b>节点级查询</b>（Node Level）— 单个节点的 SLI、延迟、错误率
 *       对应书中 Ch17 §17.3 "节点级 SLI 监控"</li>
 *   <li><b>任务级查询</b>（Task Level）— 任务的成本、成功率、耗时分布
 *       对应书中 Ch17 §17.3 "任务级成本分析"</li>
 *   <li><b>业务级查询</b>（Business Level）— 业务指标（每日 API 调用量、平均响应时间）
 *       对应书中 Ch17 §17.3 "业务级仪表盘"</li>
 * </ul>
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li>支持时间范围、过滤条件、聚合函数</li>
 *   <li>提供链式调用 API，查询构建后通过 {@link #execute()} 执行</li>
 *   <li>内置数据存储（ConcurrentHashMap），支持模拟数据查询</li>
 *   <li>可通过 {@link #registerDataSource(String, Function)} 注册自定义数据源</li>
 * </ul>
 *
 * <p>示例用法：
 * <pre>{@code
 * queryBuilder.nodeLevel()
 *     .forNode("llm-reasoning-001")
 *     .inLast(1, ChronoUnit.HOURS)
 *     .aggregateBy(Avg.class)
 *     .execute();
 * }</pre>
 *
 * <p>对应书中 Ch17 §17.3 仪表盘与告警。
 */
@Component
public class DashboardQueryBuilder {

    private static final Logger log = LoggerFactory.getLogger(DashboardQueryBuilder.class);

    /** 节点指标存储：nodeId → 指标历史数据 */
    private final Map<String, List<NodeMetric>> nodeMetrics = new ConcurrentHashMap<>();

    /** 任务指标存储：taskId → 任务指标数据 */
    private final Map<String, TaskMetric> taskMetrics = new ConcurrentHashMap<>();

    /** 业务指标存储：metricName → 时间序列数据 */
    private final Map<String, List<DataPoint>> businessMetrics = new ConcurrentHashMap<>();

    /** 自定义数据源注册表 */
    private final Map<String, Function<QueryContext, QueryResult>> dataSources = new ConcurrentHashMap<>();

    /** 告警阈值配置 */
    private final Map<String, AlertThreshold> alertThresholds = new ConcurrentHashMap<>();

    public DashboardQueryBuilder() {
        initializeDefaultDataSources();
        initializeAlertThresholds();
        log.info("[仪表盘] DashboardQueryBuilder 初始化完成");
    }

    // ========== 公共入口方法 ==========

    /**
     * 构建节点级查询
     */
    public NodeQueryBuilder nodeLevel() {
        return new NodeQueryBuilder(this);
    }

    /**
     * 构建任务级查询
     */
    public TaskQueryBuilder taskLevel() {
        return new TaskQueryBuilder(this);
    }

    /**
     * 构建业务级查询
     */
    public BusinessQueryBuilder businessLevel() {
        return new BusinessQueryBuilder(this);
    }

    // ========== 数据录入接口 ==========

    /**
     * 录入节点指标数据
     */
    public void recordNodeMetric(String nodeId, String metricName, double value,
                                  Map<String, String> tags) {
        NodeMetric metric = new NodeMetric(
                nodeId, metricName, value, Instant.now(),
                tags != null ? tags : Map.of()
        );
        nodeMetrics.computeIfAbsent(nodeId, k ->
                Collections.synchronizedList(new ArrayList<>())).add(metric);

        // 限制历史数据量
        List<NodeMetric> metrics = nodeMetrics.get(nodeId);
        if (metrics.size() > 10000) {
            metrics.subList(0, metrics.size() - 10000).clear();
        }
    }

    /**
     * 录入任务指标数据
     */
    public void recordTaskMetric(String taskId, String agentId, String status,
                                  long durationMs, double cost, Map<String, String> tags) {
        TaskMetric metric = new TaskMetric(
                taskId, agentId, status, durationMs, cost,
                Instant.now(), tags != null ? tags : Map.of()
        );
        taskMetrics.put(taskId, metric);
    }

    /**
     * 录入业务指标数据
     */
    public void recordBusinessMetric(String metricName, double value,
                                     Map<String, String> tags) {
        DataPoint point = new DataPoint(value, Instant.now(),
                tags != null ? tags : Map.of());
        businessMetrics.computeIfAbsent(metricName, k ->
                Collections.synchronizedList(new ArrayList<>())).add(point);

        List<DataPoint> points = businessMetrics.get(metricName);
        if (points.size() > 100000) {
            points.subList(0, points.size() - 100000).clear();
        }
    }

    // ========== 数据源注册 ==========

    /**
     * 注册自定义数据源
     */
    public void registerDataSource(String name, Function<QueryContext, QueryResult> source) {
        dataSources.put(name, source);
        log.info("[仪表盘] 注册数据源: {}", name);
    }

    // ========== 告警配置 ==========

    /**
     * 设置告警阈值
     */
    public void setAlertThreshold(String metricName, double warningThreshold,
                                   double criticalThreshold) {
        alertThresholds.put(metricName, new AlertThreshold(warningThreshold, criticalThreshold));
        log.info("[仪表盘] 设置告警阈值: metric={}, warning={}, critical={}",
                metricName, warningThreshold, criticalThreshold);
    }

    /**
     * 获取告警状态
     */
    public List<AlertItem> checkAlerts() {
        List<AlertItem> alerts = new ArrayList<>();

        for (Map.Entry<String, AlertThreshold> entry : alertThresholds.entrySet()) {
            String metricName = entry.getKey();
            AlertThreshold threshold = entry.getValue();

            List<DataPoint> data = businessMetrics.getOrDefault(metricName, List.of());
            if (data.isEmpty()) continue;

            double latestValue = data.get(data.size() - 1).value();
            Instant timestamp = data.get(data.size() - 1).timestamp();

            AlertLevel level;
            if (latestValue >= threshold.criticalThreshold()) {
                level = AlertLevel.CRITICAL;
            } else if (latestValue >= threshold.warningThreshold()) {
                level = AlertLevel.WARNING;
            } else {
                continue;
            }

            alerts.add(new AlertItem(metricName, level, latestValue,
                    threshold.warningThreshold(), threshold.criticalThreshold(), timestamp));

            double triggerThreshold = level == AlertLevel.CRITICAL
                    ? threshold.criticalThreshold() : threshold.warningThreshold();
            log.warn("[仪表盘] 告警触发: metric={}, level={}, value={}, threshold={}",
                    metricName, level,
                    String.format("%.2f", latestValue),
                    String.format("%.2f", triggerThreshold));
        }

        return alerts;
    }

    // ========== 初始化 ==========

    private void initializeDefaultDataSources() {
        registerDataSource("node_sli", ctx -> {
            List<NodeMetric> metrics = nodeMetrics.getOrDefault(ctx.nodeId(), List.of());
            List<NodeMetric> filtered = metrics.stream()
                    .filter(m -> !m.timestamp().isBefore(ctx.startTime())
                            && !m.timestamp().isAfter(ctx.endTime()))
                    .toList();

            return new QueryResult(
                    filtered.stream().map(NodeMetric::value).toList(),
                    filtered.size(),
                    "node_sli"
            );
        });

        registerDataSource("task_metrics", ctx -> {
            List<TaskMetric> tasks = taskMetrics.values().stream()
                    .filter(t -> !t.timestamp().isBefore(ctx.startTime())
                            && !t.timestamp().isAfter(ctx.endTime()))
                    .toList();

            return new QueryResult(
                    tasks.stream().map(TaskMetric::durationMs).map(Double::valueOf).toList(),
                    tasks.size(),
                    "task_metrics"
            );
        });

        registerDataSource("business_kpi", ctx -> {
            List<DataPoint> points = businessMetrics.getOrDefault(ctx.metricName(), List.of());
            List<DataPoint> filtered = points.stream()
                    .filter(p -> !p.timestamp().isBefore(ctx.startTime())
                            && !p.timestamp().isAfter(ctx.endTime()))
                    .toList();

            return new QueryResult(
                    filtered.stream().map(DataPoint::value).toList(),
                    filtered.size(),
                    "business_kpi"
            );
        });
    }

    private void initializeAlertThresholds() {
        setAlertThreshold("error_rate", 0.05, 0.10);
        setAlertThreshold("latency_p99_ms", 5000.0, 15000.0);
        setAlertThreshold("cost_per_task", 10.0, 50.0);
    }

    // ========== 聚合函数 ==========

    /**
     * 聚合函数枚举
     */
    public enum AggregationFunction {
        AVG, SUM, MIN, MAX, COUNT, P50, P95, P99
    }

    /**
     * 应用聚合函数到数值列表
     */
    double applyAggregation(List<Double> values, AggregationFunction function) {
        if (values == null || values.isEmpty()) return 0.0;

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        return switch (function) {
            case AVG -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case MIN -> sorted.get(0);
            case MAX -> sorted.get(sorted.size() - 1);
            case COUNT -> (double) values.size();
            case P50 -> percentile(sorted, 0.50);
            case P95 -> percentile(sorted, 0.95);
            case P99 -> percentile(sorted, 0.99);
        };
    }

    private double percentile(List<Double> sorted, double p) {
        int index = (int) (sorted.size() * p);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    // ========== 数据模型 ==========

    /**
     * 节点指标
     */
    public record NodeMetric(String nodeId, String metricName, double value,
                             Instant timestamp, Map<String, String> tags) {}

    /**
     * 任务指标
     */
    public record TaskMetric(String taskId, String agentId, String status,
                             long durationMs, double cost,
                             Instant timestamp, Map<String, String> tags) {}

    /**
     * 业务指标数据点
     */
    public record DataPoint(double value, Instant timestamp, Map<String, String> tags) {}

    /**
     * 查询上下文（传递给数据源）
     */
    public record QueryContext(String nodeId, String metricName,
                                Instant startTime, Instant endTime,
                                Map<String, String> filters) {}

    /**
     * 查询结果
     */
    public record QueryResult(List<Double> values, int count, String sourceName) {}

    /**
     * 告警条目
     */
    public record AlertItem(String metricName, AlertLevel level, double currentValue,
                             double warningThreshold, double criticalThreshold,
                             Instant timestamp) {}

    /**
     * 告警级别
     */
    public enum AlertLevel {
        WARNING, CRITICAL
    }

    /**
     * 告警阈值
     */
    public record AlertThreshold(double warningThreshold, double criticalThreshold) {}

    // ========== 节点级查询构建器 ==========

    /**
     * 节点级查询构建器 — 支持单个节点的 SLI、延迟、错误率查询
     * 对应书中 Ch17 §17.3 节点级仪表盘
     */
    public static class NodeQueryBuilder {
        private final DashboardQueryBuilder parent;
        private String nodeId;
        private Instant startTime = Instant.now().minusSeconds(3600);
        private Instant endTime = Instant.now();
        private String metricName;
        private AggregationFunction aggregation;
        private final Map<String, String> filters = new HashMap<>();

        NodeQueryBuilder(DashboardQueryBuilder parent) {
            this.parent = parent;
        }

        /** 指定节点 ID */
        public NodeQueryBuilder forNode(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /** 指定时间范围（过去 N 个时间单位） */
        public NodeQueryBuilder inLast(long amount, ChronoUnit unit) {
            this.startTime = Instant.now().minus(amount, unit);
            this.endTime = Instant.now();
            return this;
        }

        /** 指定具体时间范围 */
        public NodeQueryBuilder between(Instant start, Instant end) {
            this.startTime = start;
            this.endTime = end;
            return this;
        }

        /** 指定指标名称 */
        public NodeQueryBuilder metric(String metricName) {
            this.metricName = metricName;
            return this;
        }

        /** 指定聚合函数 */
        public NodeQueryBuilder aggregateBy(AggregationFunction function) {
            this.aggregation = function;
            return this;
        }

        /** 添加过滤条件 */
        public NodeQueryBuilder filter(String key, String value) {
            this.filters.put(key, value);
            return this;
        }

        /** 执行查询 */
        public DashboardQueryResult execute() {
            log.info("[仪表盘] 执行节点级查询: nodeId={}, metric={}, aggregation={}",
                    nodeId, metricName, aggregation);

            QueryContext ctx = new QueryContext(nodeId, metricName, startTime, endTime, filters);
            QueryResult rawResult = parent.dataSources.getOrDefault("node_sli",
                    c -> new QueryResult(List.of(), 0, "empty")).apply(ctx);

            double aggregatedValue = 0.0;
            if (aggregation != null && !rawResult.values().isEmpty()) {
                aggregatedValue = parent.applyAggregation(rawResult.values(), aggregation);
            } else if (!rawResult.values().isEmpty()) {
                aggregatedValue = rawResult.values().get(rawResult.values().size() - 1);
            }

            return new DashboardQueryResult(
                    "NODE",
                    nodeId,
                    metricName != null ? metricName : "all",
                    aggregatedValue,
                    rawResult.count(),
                    startTime,
                    endTime,
                    filters
            );
        }
    }

    // ========== 任务级查询构建器 ==========

    /**
     * 任务级查询构建器 — 支持任务的成本、成功率、耗时分布查询
     * 对应书中 Ch17 §17.3 任务级仪表盘
     */
    public static class TaskQueryBuilder {
        private final DashboardQueryBuilder parent;
        private String agentId;
        private String status;
        private Instant startTime = Instant.now().minusSeconds(86400);
        private Instant endTime = Instant.now();
        private AggregationFunction aggregation;
        private final Map<String, String> filters = new HashMap<>();

        TaskQueryBuilder(DashboardQueryBuilder parent) {
            this.parent = parent;
        }

        /** 指定 Agent ID */
        public TaskQueryBuilder forAgent(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /** 指定任务状态过滤 */
        public TaskQueryBuilder withStatus(String status) {
            this.status = status;
            return this;
        }

        /** 指定时间范围 */
        public TaskQueryBuilder inLast(long amount, ChronoUnit unit) {
            this.startTime = Instant.now().minus(amount, unit);
            this.endTime = Instant.now();
            return this;
        }

        /** 指定具体时间范围 */
        public TaskQueryBuilder between(Instant start, Instant end) {
            this.startTime = start;
            this.endTime = end;
            return this;
        }

        /** 指定聚合函数 */
        public TaskQueryBuilder aggregateBy(AggregationFunction function) {
            this.aggregation = function;
            return this;
        }

        /** 添加过滤条件 */
        public TaskQueryBuilder filter(String key, String value) {
            this.filters.put(key, value);
            return this;
        }

        /** 执行查询 */
        public DashboardQueryResult execute() {
            log.info("[仪表盘] 执行任务级查询: agentId={}, status={}, aggregation={}",
                    agentId, status, aggregation);

            // 从任务指标存储中查询
            List<TaskMetric> matchedTasks = parent.taskMetrics.values().stream()
                    .filter(t -> agentId == null || agentId.equals(t.agentId()))
                    .filter(t -> status == null || status.equals(t.status()))
                    .filter(t -> !t.timestamp().isBefore(startTime) && !t.timestamp().isAfter(endTime))
                    .toList();

            // 计算聚合值
            double aggregatedValue = 0.0;
            if (aggregation != null) {
                List<Double> durations = matchedTasks.stream()
                        .map(t -> (double) t.durationMs())
                        .toList();
                aggregatedValue = parent.applyAggregation(durations, aggregation);
            }

            // 计算成功率
            long successCount = matchedTasks.stream()
                    .filter(t -> "COMPLETED".equals(t.status()))
                    .count();
            double successRate = matchedTasks.isEmpty() ? 0 :
                    (double) successCount / matchedTasks.size();

            return new DashboardQueryResult(
                    "TASK",
                    agentId != null ? agentId : "all",
                    "task_duration",
                    aggregatedValue,
                    matchedTasks.size(),
                    startTime,
                    endTime,
                    Map.of("successRate", String.valueOf(successRate))
            );
        }
    }

    // ========== 业务级查询构建器 ==========

    /**
     * 业务级查询构建器 — 支持业务指标查询（如每日 API 调用量、平均响应时间）
     * 对应书中 Ch17 §17.3 业务级仪表盘
     */
    public static class BusinessQueryBuilder {
        private final DashboardQueryBuilder parent;
        private String metricName;
        private Instant startTime = Instant.now().minusSeconds(86400);
        private Instant endTime = Instant.now();
        private AggregationFunction aggregation;
        private final Map<String, String> filters = new HashMap<>();

        BusinessQueryBuilder(DashboardQueryBuilder parent) {
            this.parent = parent;
        }

        /** 指定业务指标名称 */
        public BusinessQueryBuilder forMetric(String metricName) {
            this.metricName = metricName;
            return this;
        }

        /** 指定时间范围 */
        public BusinessQueryBuilder inLast(long amount, ChronoUnit unit) {
            this.startTime = Instant.now().minus(amount, unit);
            this.endTime = Instant.now();
            return this;
        }

        /** 指定具体时间范围 */
        public BusinessQueryBuilder between(Instant start, Instant end) {
            this.startTime = start;
            this.endTime = end;
            return this;
        }

        /** 指定聚合函数 */
        public BusinessQueryBuilder aggregateBy(AggregationFunction function) {
            this.aggregation = function;
            return this;
        }

        /** 添加过滤条件 */
        public BusinessQueryBuilder filter(String key, String value) {
            this.filters.put(key, value);
            return this;
        }

        /** 执行查询 */
        public DashboardQueryResult execute() {
            log.info("[仪表盘] 执行业务级查询: metric={}, aggregation={}",
                    metricName, aggregation);

            List<DataPoint> data = parent.businessMetrics.getOrDefault(metricName, List.of());
            List<DataPoint> filtered = data.stream()
                    .filter(p -> !p.timestamp().isBefore(startTime)
                            && !p.timestamp().isAfter(endTime))
                    .toList();

            double aggregatedValue = 0.0;
            if (aggregation != null && !filtered.isEmpty()) {
                List<Double> values = filtered.stream().map(DataPoint::value).toList();
                aggregatedValue = parent.applyAggregation(values, aggregation);
            } else if (!filtered.isEmpty()) {
                aggregatedValue = filtered.get(filtered.size() - 1).value();
            }

            // 计算趋势（与上一个时间段对比）
            Instant prevStart = startTime.minus(
                    Duration.between(startTime, endTime).toMillis(),
                    ChronoUnit.MILLIS);
            List<DataPoint> prevData = data.stream()
                    .filter(p -> !p.timestamp().isBefore(prevStart)
                            && p.timestamp().isBefore(startTime))
                    .toList();

            double trend = 0.0;
            if (!prevData.isEmpty() && !filtered.isEmpty()) {
                double prevAvg = prevData.stream().mapToDouble(DataPoint::value).average().orElse(0);
                double currAvg = filtered.stream().mapToDouble(DataPoint::value).average().orElse(0);
                trend = prevAvg == 0 ? 0 : (currAvg - prevAvg) / prevAvg * 100;
            }

            return new DashboardQueryResult(
                    "BUSINESS",
                    metricName,
                    metricName,
                    aggregatedValue,
                    filtered.size(),
                    startTime,
                    endTime,
                    Map.of("trendPercent", String.valueOf(trend))
            );
        }
    }

    // ========== 查询结果 ==========

    /**
     * 仪表盘查询结果
     */
    public record DashboardQueryResult(
            String level,
            String targetId,
            String metricName,
            double value,
            int dataPoints,
            Instant startTime,
            Instant endTime,
            Map<String, String> extraInfo
    ) {
        public String summary() {
            return String.format("[%s] %s/%s = %.2f (%d 数据点, %s → %s)",
                    level, targetId, metricName, value, dataPoints,
                    startTime, endTime);
        }
    }
}