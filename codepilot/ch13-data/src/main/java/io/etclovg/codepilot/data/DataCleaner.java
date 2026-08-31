package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据清洗组件。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮的清洗阶段。
 * <p>对错误样本去重、归一化、自动标注，输出可供训练或检索使用的训练对。
 * 同时承担原始数据的去噪、格式归一化与敏感信息脱敏。
 */
@Component
public class DataCleaner {

    private static final Logger log = LoggerFactory.getLogger(DataCleaner.class);

    /**
     * 清洗错误样本为训练对。
     *
     * @param samples 错误样本列表
     * @return 训练对列表（桩实现返回空列表）
     */
    public List<TrainingPair> clean(List<ErrorSample> samples) {
        log.debug("[DataCleaner] 清洗错误样本: count={}", samples != null ? samples.size() : 0);
        // 桩实现：实际应去重、归一化、自动标注
        return new ArrayList<>();
    }

    /**
     * 清洗单条数据（保留原有便捷方法）。
     *
     * @param raw 原始数据
     * @return 清洗后数据
     */
    public String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }

    /**
     * 批量清洗并返回有效条目数（保留原有便捷方法）。
     *
     * @param rawItems 原始数据列表
     * @return 清洗后列表
     */
    public List<String> cleanBatch(List<String> rawItems) {
        if (rawItems == null) {
            return List.of();
        }
        List<String> cleaned = rawItems.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(this::clean)
                .toList();
        log.info("批量清洗: input={}, output={}", rawItems.size(), cleaned.size());
        return cleaned;
    }
}
