package io.etclovg.codepilot.planning;

/**
 * 计划预评估维度。
 * <p>对应书中 Ch14 §14.5 —— 低成本预验证的三维评分：
 * 完整性、可操作性、依赖合理性。
 *
 * <p>与章节代码对齐：普通类（非 enum），支持 {@code new EvalDimension(name, question, weight)}。
 */
public class EvalDimension {

    private final String name;
    private final String question;
    private final double weight;

    public EvalDimension(String name, String question, double weight) {
        this.name = name;
        this.question = question;
        this.weight = weight;
    }

    public String getName() {
        return name;
    }

    public String getQuestion() {
        return question;
    }

    public double getWeight() {
        return weight;
    }
}
