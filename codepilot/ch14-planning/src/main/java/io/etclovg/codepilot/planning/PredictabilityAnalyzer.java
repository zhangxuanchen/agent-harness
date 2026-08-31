package io.etclovg.codepilot.planning;

import org.springframework.stereotype.Component;

/**
 * 可预测性分析器。
 * <p>对应书中 Ch14 §14.2.2 —— 判断任务的工具返回是否可预测。
 * 可预测的工具返回（如 "200 OK"、明确的 JSON 结构）适合 ReWOO 范式；
 * 不可预测的工具返回（如摘要文本、截图描述）不适合。
 */
@Component
public class PredictabilityAnalyzer {

    /**
     * 判断工具调用的可预测性。
     *
     * @param toolName 工具名
     * @param sampleReturn 示例返回值
     * @return 可预测返回 true
     */
    public boolean isPredictable(String toolName, String sampleReturn) {
        if (toolName == null || sampleReturn == null) {
            return false;
        }
        // 桩实现：按工具名关键字判断
        String lowerName = toolName.toLowerCase();
        return lowerName.contains("query")
            || lowerName.contains("read")
            || lowerName.contains("list")
            || lowerName.contains("check")
            || lowerName.contains("get");
    }
}
