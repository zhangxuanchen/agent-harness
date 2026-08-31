package io.etclovg.codepilot.core;

/**
 * ETCLOVG 七层标识枚举。
 * <p>全书所有 Advisor 和组件均按此枚举归层，用于可观测性和配置管理。
 *
 * <ul>
 *   <li>{@link #E} 执行环境 Execution</li>
 *   <li>{@link #T} 工具接口 Tooling</li>
 *   <li>{@link #C} 上下文记忆 Context</li>
 *   <li>{@link #L} 生命周期编排 Lifecycle</li>
 *   <li>{@link #O} 可观测性 Observability</li>
 *   <li>{@link #V} 验证评估 Verification</li>
 *   <li>{@link #G} 治理安全 Governance</li>
 * </ul>
 */
public enum Layer {
    E("执行环境"), T("工具接口"), C("上下文记忆"),
    L("生命周期编排"), O("可观测性"), V("验证评估"), G("治理安全");

    private final String displayName;

    Layer(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
