package io.etclovg.codepilot.data;

/**
 * 工具候选。
 * <p>对应书中 Ch13 §13.2 —— 工具制造流水线中的候选工具表示。
 * <p>描述工具制造阶段产生的候选工具（名称、描述、参数 schema、来源、置信度），
 * 并携带可变 {@link Stage} 状态，供五阶段流水线（O→Agent→V→G→T）流转使用。
 */
public class ToolCandidate {

    private final String name;
    private final String description;
    private final String schemaJson;
    private final String source;
    private final double confidence;
    private Stage status;

    /**
     * 构造工具候选。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param schemaJson  参数 JSON Schema
     * @param source      来源（如 llm-generated、template、manual）
     * @param confidence  置信度（0-1）
     */
    public ToolCandidate(String name, String description, String schemaJson,
                         String source, double confidence) {
        this.name = name;
        this.description = description;
        this.schemaJson = schemaJson;
        this.source = source;
        this.confidence = confidence;
        this.status = Stage.DRAFT;
    }

    /**
     * 构造带默认字段的候选工具。
     *
     * @param name        名称
     * @param description 描述
     * @return 候选工具
     */
    public static ToolCandidate of(String name, String description) {
        return new ToolCandidate(name, description, "{}", "llm-generated", 0.0);
    }

    /** @return 工具名称 */
    public String name() {
        return name;
    }

    /** @return 工具描述 */
    public String description() {
        return description;
    }

    /** @return 参数 JSON Schema */
    public String schemaJson() {
        return schemaJson;
    }

    /** @return 来源 */
    public String source() {
        return source;
    }

    /** @return 置信度 */
    public double confidence() {
        return confidence;
    }

    /** @return 当前流水线阶段 */
    public Stage status() {
        return status;
    }

    /**
     * 设置流水线阶段。
     *
     * @param status 阶段标记
     */
    public void setStatus(Stage status) {
        this.status = status;
    }

    /**
     * 是否达到发布阈值。
     *
     * @param threshold 置信度阈值
     * @return 达到返回 true
     */
    public boolean isPublishable(double threshold) {
        return confidence >= threshold;
    }
}
