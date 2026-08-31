package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 错误收集器。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮 O 层错误采集。
 * <p>拦截 V 层验证失败和用户负面反馈，结构化存储为
 * (input, expected, actual, error_type)，供数据清洗与模型微调使用。
 * 同时在批量处理过程中收集所有错误，避免单点失败中断整体流程。
 */
@Component
public class ErrorCollector {

    private static final Logger log = LoggerFactory.getLogger(ErrorCollector.class);

    private final List<ProcessingError> errors = Collections.synchronizedList(new ArrayList<>());

    /**
     * 采集时间窗口内的错误样本。
     *
     * @param window 时间窗口
     * @return 错误样本列表（桩实现返回空列表）
     */
    public List<ErrorSample> collect(Duration window) {
        log.debug("[ErrorCollector] 采集错误样本: window={}h", window != null ? window.toHours() : -1);
        // 桩实现：实际应从错误存储中按时间窗口聚合
        return new ArrayList<>();
    }

    /**
     * 记录一个错误（保留原有便捷方法）。
     *
     * @param itemId  出错的条目 ID
     * @param stage   出错阶段
     * @param message 错误信息
     */
    public void record(String itemId, String stage, String message) {
        errors.add(new ProcessingError(itemId, stage, message));
        log.debug("收集错误: item={}, stage={}, msg={}", itemId, stage, message);
    }

    /**
     * 获取已收集错误数量（保留原有便捷方法）。
     *
     * @return 错误数量
     */
    public int count() {
        return errors.size();
    }

    /**
     * 获取错误快照（保留原有便捷方法）。
     *
     * @return 不可变错误列表
     */
    public List<ProcessingError> snapshot() {
        return List.copyOf(errors);
    }

    /**
     * 清空已收集错误（保留原有便捷方法）。
     */
    public void clear() {
        errors.clear();
    }

    /**
     * 处理错误记录。
     *
     * @param itemId  条目 ID
     * @param stage   阶段
     * @param message 错误信息
     */
    public record ProcessingError(String itemId, String stage, String message) {
    }
}
