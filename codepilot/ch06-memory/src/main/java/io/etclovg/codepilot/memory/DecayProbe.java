package io.etclovg.codepilot.memory;

import java.util.function.Predicate;

/**
 * 衰减探针。
 * <p>用于检测上下文腐烂的探针——在指定步骤注入问题，观察模型回答的准确率。
 *
 * <p>页面参考：Ch6 §6.4.1 上下文腐烂诊断
 */
public record DecayProbe(
        String id,
        String type,
        String question,
        String expectedAnswer,
        int priority,
        int injectionInterval,
        Predicate<String> validator
) {

    /** 兼容别名——与 record 字段 injectionInterval 的自动方法并存 */
    public int injectionFrequency() {
        return injectionInterval;
    }

    /**
     * 判断当前步骤是否应注入此探针。
     */
    public boolean shouldInject(int step) {
        return injectionInterval > 0 && step % injectionInterval == 0;
    }

    /**
     * 验证回答是否正确。
     */
    public boolean validate(String response) {
        if (validator != null) {
            return validator.test(response);
        }
        return response != null && response.contains(expectedAnswer);
    }

    /**
     * 计算匹配分数，基于 response 的语义匹配程度。
     */
    public double calculateMatchScore(String response) {
        if (response == null || response.isEmpty()) {
            return 0.0;
        }
        if (validator != null) {
            return validator.test(response) ? 1.0 : 0.0;
        }
        return response.contains(expectedAnswer) ? 1.0 : 0.0;
    }

    public static DecayProbe of(String id, String type, String question, String expectedAnswer) {
        return new DecayProbe(id, type, question, expectedAnswer, 0, 5,
                r -> r != null && r.contains(expectedAnswer));
    }

    public static DecayProbe goalRecall(String goal) {
        return new DecayProbe("goal-recall", "goal", "当前任务目标是什么？", goal, 10, 5,
                r -> r != null && r.contains(goal));
    }

    public static DecayProbe constraintRecall(String constraint) {
        return new DecayProbe("constraint-recall", "constraint", "有哪些关键约束？", constraint, 8, 5,
                r -> r != null && r.contains(constraint));
    }
}