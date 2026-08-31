package io.etclovg.codepilot.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成本归因中间件：在执行链路中自动进行成本归因
 * 对应书中 Ch08 §8.3 —— 成本可观测性设计
 */
@Component("costTracker")
public class CostAttributionMiddleware {

    private static final Logger log = LoggerFactory.getLogger(CostAttributionMiddleware.class);

    private final Map<String, CostBreakdown> costByTask = new ConcurrentHashMap<>();
    private final Map<String, CostBreakdown> costByAgent = new ConcurrentHashMap<>();
    private final Map<String, CostBreakdown> costByTool = new ConcurrentHashMap<>();

    private final BigDecimal inputCostPerToken;
    private final BigDecimal outputCostPerToken;

    public CostAttributionMiddleware() {
        this(BigDecimal.valueOf(0.005), BigDecimal.valueOf(0.015));
    }

    public CostAttributionMiddleware(BigDecimal inputCostPerToken, BigDecimal outputCostPerToken) {
        this.inputCostPerToken = inputCostPerToken;
        this.outputCostPerToken = outputCostPerToken;
    }

    public CostAttributionRecord record(
            String taskId, String agentId, String toolName,
            long inputTokens, long outputTokens, long durationMs
    ) {
        BigDecimal inputCost = inputCostPerToken
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputCostPerToken
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
        BigDecimal totalCost = inputCost.add(outputCost);

        CostAttributionRecord record = new CostAttributionRecord(
                UUID.randomUUID().toString().substring(0, 8),
                taskId, agentId, toolName,
                inputTokens, outputTokens, totalCost, durationMs,
                Instant.now()
        );

        accumulate(costByTask, taskId, totalCost);
        accumulate(costByAgent, agentId, totalCost);
        accumulate(costByTool, toolName, totalCost);

        log.debug("[CostAttribution] 归因记录: task={}, agent={}, tool={}, cost=${}",
                taskId, agentId, toolName,
                totalCost.setScale(6, RoundingMode.HALF_UP));

        return record;
    }

    public CostBreakdown getTaskBreakdown(String taskId) {
        return costByTask.getOrDefault(taskId, new CostBreakdown(taskId, BigDecimal.ZERO, 0));
    }

    public CostBreakdown getAgentBreakdown(String agentId) {
        return costByAgent.getOrDefault(agentId, new CostBreakdown(agentId, BigDecimal.ZERO, 0));
    }

    public CostBreakdown getToolBreakdown(String toolName) {
        return costByTool.getOrDefault(toolName, new CostBreakdown(toolName, BigDecimal.ZERO, 0));
    }

    public BigDecimal getTotalCost() {
        return costByTask.values().stream()
                .map(CostBreakdown::totalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Map<String, CostBreakdown> getAllTaskBreakdowns() {
        return Collections.unmodifiableMap(costByTask);
    }

    public Map<String, CostBreakdown> getAllAgentBreakdowns() {
        return Collections.unmodifiableMap(costByAgent);
    }

    public Map<String, CostBreakdown> getAllToolBreakdowns() {
        return Collections.unmodifiableMap(costByTool);
    }

    public void reset() {
        costByTask.clear();
        costByAgent.clear();
        costByTool.clear();
        log.info("[CostAttributionMiddleware] 所有归因数据已重置");
    }

    private void accumulate(Map<String, CostBreakdown> map, String key, BigDecimal cost) {
        map.merge(key, new CostBreakdown(key, cost, 1),
                (existing, incoming) -> new CostBreakdown(
                        key,
                        existing.totalCost().add(incoming.totalCost()),
                        existing.callCount() + incoming.callCount()
                ));
    }

    public record CostAttributionRecord(
            String id, String taskId, String agentId,
            String toolName, long inputTokens, long outputTokens,
            BigDecimal cost, long durationMs, Instant timestamp
    ) {}

    public record CostBreakdown(
            String key, BigDecimal totalCost, int callCount
    ) {}
}