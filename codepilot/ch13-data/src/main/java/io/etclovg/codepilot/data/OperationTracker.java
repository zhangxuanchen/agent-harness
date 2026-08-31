package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 操作追踪器。
 * <p>对应书中 Ch13 §13.2 —— O 层发现：埋点追踪高频人工操作。
 * <p>当某操作模式在时间窗口内重复达到阈值次时，标记为"可工具化候选"，
 * 供 {@link ToolManufacturingPipeline} 制造。同时记录数据写入、删除、训练触发
 * 等关键操作的发起者、时间与参数，供审计与回溯使用。
 */
@Component
public class OperationTracker {

    private static final Logger log = LoggerFactory.getLogger(OperationTracker.class);

    private final List<Operation> operations = Collections.synchronizedList(new ArrayList<>());

    /**
     * 发现高频操作模式。
     * <p>追踪 {@code operationDomain} 域内最近 {@code days} 天内出现频次
     * 不低于 {@code threshold} 的操作模式，作为工具化候选。
     *
     * @param operationDomain 操作域
     * @param days            时间窗口（天）
     * @param threshold       频次阈值
     * @return 高频操作模式列表（桩实现返回空列表）
     */
    public List<OperationPattern> findFrequent(String operationDomain, int days, int threshold) {
        log.debug("[OperationTracker] 查找高频操作: domain={}, days={}, threshold={}",
                operationDomain, days, threshold);
        // 桩实现：实际应从操作历史聚合统计，返回达到阈值的模式
        return new ArrayList<>();
    }

    /**
     * 记录一次操作。
     *
     * @param operator  操作者
     * @param operation 操作名称
     * @param target    操作目标
     */
    public void track(String operator, String operation, String target) {
        operations.add(new Operation(operator, operation, target, Instant.now()));
        log.debug("追踪操作: operator={}, op={}, target={}", operator, operation, target);
    }

    /**
     * 获取操作历史快照。
     *
     * @return 不可变操作列表
     */
    public List<Operation> snapshot() {
        return List.copyOf(operations);
    }

    /**
     * 操作记录。
     *
     * @param operator  操作者
     * @param operation 操作名称
     * @param target    操作目标
     * @param timestamp 时间戳
     */
    public record Operation(String operator, String operation, String target, Instant timestamp) {
    }
}
