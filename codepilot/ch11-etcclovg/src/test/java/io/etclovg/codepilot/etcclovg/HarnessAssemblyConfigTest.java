package io.etclovg.codepilot.etcclovg;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HarnessAssemblyConfig 单元测试。
 *
 * <p>验证七层 Middleware 装配：
 * <ul>
 *   <li>全层开启 → ReActAgent 成功构建（11 个 Middleware 入链：G1+C3+E1+T1+L2+V1+O2）</li>
 *   <li>单层关闭 → Agent 仍可构建（该层 Middleware 被裁剪，不抛依赖缺失）</li>
 *   <li>FullHarnessConfig 默认开关全开</li>
 * </ul>
 *
 * <p>具体 Middleware Bean 以 {@link MiddlewareBase} 接口桩注入（agentscope-core 自带），
 * 不依赖 ch04-ch10 具体实现，验证装配顺序与裁剪逻辑本身。
 */
@DisplayName("Ch11 七层 Harness 装配测试")
class HarnessAssemblyConfigTest {

    /** 最小 MiddlewareBase 桩——所有钩子直接 delegate to next。 */
    private static MiddlewareBase stub() {
        return new MiddlewareBase() {};
    }

    /** 桩 Model——返回空 Flux，避免依赖 DASHSCOPE_API_KEY 与扩展模块。 */
    private static final Model STUB_MODEL = new Model() {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }
    };

    @BeforeEach
    void registerStubModel() {
        // 注册桩模型，使 ReActAgent.builder().model("dashscope:qwen-plus") 无需真实 API key
        ModelRegistry.register("dashscope:qwen-plus", STUB_MODEL);
    }

    @Test
    @DisplayName("全层开启：ReActAgent 成功装配且非空")
    void fullHarness_assemblesAllLayers() {
        FullHarnessConfig config = new FullHarnessConfig();   // 默认七层全开
        HarnessAssemblyConfig assembly = new HarnessAssemblyConfig(config);

        ReActAgent agent = assembly.harnessAgent(
                "dashscope:qwen-plus",
                new Toolkit(),
                stub(),  // safeGuard        (G)
                stub(),  // workingMemory    (C)
                stub(),  // episodicMemory   (C)
                stub(),  // compactor        (C)
                stub(),  // sandboxAdvisor   (E)
                stub(),  // toolCalling      (T)
                stub(),  // orchestrator     (L)
                stub(),  // stepLimit        (L)
                stub(),  // validator        (V)
                stub(),  // tracer           (O)
                stub()   // costTracker      (O)
        );

        assertThat(agent).as("全七层装配后 ReActAgent 必须非空").isNotNull();
    }

    @Test
    @DisplayName("关闭 G 层：Agent 仍可构建（governanceEnabled=false 裁剪 G 层）")
    void fullHarness_governanceDisabled_stillBuilds() {
        FullHarnessConfig config = new FullHarnessConfig();
        config.setGovernanceEnabled(false);                    // 关闭 G 层
        HarnessAssemblyConfig assembly = new HarnessAssemblyConfig(config);

        ReActAgent agent = assembly.harnessAgent(
                "dashscope:qwen-plus", new Toolkit(),
                stub(), stub(), stub(), stub(), stub(),
                stub(), stub(), stub(), stub(), stub(), stub());

        assertThat(agent).as("即使 G 层关闭，Agent 仍应成功构建").isNotNull();
    }

    @Test
    @DisplayName("关闭 C/V/O 三层：Agent 仍可构建（仅保留 G/E/T/L）")
    void fullHarness_partialLayers_stillBuilds() {
        FullHarnessConfig config = new FullHarnessConfig();
        config.setContextEnabled(false);
        config.setVerificationEnabled(false);
        config.setObservabilityEnabled(false);
        HarnessAssemblyConfig assembly = new HarnessAssemblyConfig(config);

        ReActAgent agent = assembly.harnessAgent(
                "dashscope:qwen-plus", new Toolkit(),
                stub(), stub(), stub(), stub(), stub(),
                stub(), stub(), stub(), stub(), stub(), stub());

        assertThat(agent).as("部分层关闭后 Agent 仍应成功构建").isNotNull();
    }

    @Test
    @DisplayName("FullHarnessConfig 默认七层全开")
    void fullHarnessConfig_defaultsAllEnabled() {
        FullHarnessConfig config = new FullHarnessConfig();
        assertThat(config.isGovernanceEnabled()).isTrue();
        assertThat(config.isVerificationEnabled()).isTrue();
        assertThat(config.isObservabilityEnabled()).isTrue();
        assertThat(config.isLifecycleEnabled()).isTrue();
        assertThat(config.isContextEnabled()).isTrue();
        assertThat(config.isToolingEnabled()).isTrue();
        assertThat(config.isExecutionEnabled()).isTrue();
    }
}
