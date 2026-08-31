package io.etclovg.codepilot.data;

/**
 * 工具注册中心接口。
 * <p>对应书中 Ch13 §13.2 —— T 层注册阶段，工具组动态注册的统一服务发现机制。
 * <p>{@link ToolManufacturingPipeline} 在 G 层审批通过后调用
 * {@link #register(ToolCandidate)} 将工具候选注册到注册表，供 Agent 运行时查找调用。
 */
public interface ToolRegistry {

    /**
     * 注册一个工具候选。
     *
     * @param candidate 通过审批的工具候选
     */
    void register(ToolCandidate candidate);
}
