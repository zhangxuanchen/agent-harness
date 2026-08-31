package io.etclovg.codepilot.etcclovg;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 七层 Harness 装配配置 —— ETCLOVG 七层协同的工程落点。
 *
 * <p>对应书中 Ch11 §11.3 KP 11.3.1。本类是 {@code @Configuration}（产出 {@code @Bean}），
 * 与同包的 {@link FullHarnessConfig}（{@code @ConfigurationProperties} 属性持有者）分工：
 * <ul>
 *   <li>{@link FullHarnessConfig} —— 持有七层开关（{@code *Enabled}），
 *       由 {@code application.yml} 的 {@code codepilot.harness.*} 注入，负责"配什么"。</li>
 *   <li>{@code HarnessAssemblyConfig}（本类）—— 把七层 {@link MiddlewareBase} Bean 按
 *       G→C→E→T→L→V→O 顺序注册到 {@link ReActAgent}，负责"怎么装"。</li>
 * </ul>
 *
 * <p><b>装配顺序的执行语义</b>（入站正向、出站逆向）：
 * <pre>
 *   SafeGuard(G) → MessageChatMemoryMiddleware(C) → VectorStoreChatMemoryMiddleware(C) → ContextCompactor(C)
 *     → SandboxAdvisor(E) → ToolCallingMiddleware(T) → ReActOrchestrator(L) → StepLimitMiddleware(L)
 *     → EmbeddedValidationAdvisor(V)
 *     → TracerMiddleware(O) → CostAttributionMiddleware(O)
 * </pre>
 * G 层用单一 {@code SafeGuard} 总入口（内部组合 Input/Output/ToolPolicy 三检查点），
 * 注册在首位：入站最先做输入检查、出站最后做输出检查（出站逆向 = 链尾先触发）。
 * E 层 {@code SandboxAdvisor} 用 {@code onActing} 钩子包裹工具执行——注册在 T 层之前，
 * 使每次工具调用前先注入 {@code sandbox.*} 隔离配置（镜像/CPU/内存/网络白名单），
 * 工具执行完 {@code doFinally} 清理沙箱。
 *
 * <p><b>V 层拆分</b>：运行时链只装 {@code EmbeddedValidationAdvisor}（每步打分，毫秒级）；
 * {@code RegressionEvaluator} 是离线批量回归评测器（普通 {@code @Component}，<b>不实现</b>
 * {@link MiddlewareBase}），由 CI/定时任务调用 {@code evaluate(suiteId)} 跑整套基线用例，
 * 不进每请求链——否则每请求跑百例套件，延迟与成本不可接受。
 *
 * <p><b>跨模块依赖说明</b>：各层具体 Middleware 实现位于 ch01-ch10 各模块：
 * G 层（ch08-observability 的 SafeGuard 总入口，内部委托 ch10-governance 检查点）、
 * C 层（ch06-memory）、E 层（ch04-sandbox 的 SandboxAdvisor）、T 层（ch02-definition/ch05-tools）、
 * L 层（ch07-orchestration、ch01 StepLimit）、V 层（ch09-evaluation）、O 层（ch08-observability）。
 * 本类以 {@link MiddlewareBase}（agentscope-core 接口）+ {@code @Qualifier} 注入具体 Bean，
 * 编译期仅需 agentscope-core；ch11-etcclovg 在 pom 中声明 ch01-ch10 依赖以提供运行期具体实现 Bean，
 * 层间仍通过接口 + Qualifier 松耦合（不直接 import 各层实现类）。
 *
 * <p><b>三层代码结构</b>（与 Ch10 锚点块格式对齐）：
 * ① 类声明 {@code @Configuration} + 装配入口；
 * ② 核心方法 {@code harnessAgent(...)} 显式列出 11 个 Middleware（七层全开时）；
 * ③ 按 {@link FullHarnessConfig} 开关动态裁剪（关闭某层 = 跳过该层 Middleware）。
 */
@Configuration
public class HarnessAssemblyConfig {                       // ── ① 类声明：@Configuration 装配入口 ──

    private static final Logger log = LoggerFactory.getLogger(HarnessAssemblyConfig.class);

    private final FullHarnessConfig config;

    public HarnessAssemblyConfig(FullHarnessConfig config) {
        this.config = config;
    }

    /** 模型标识 Bean——供 {@code @Qualifier("modelRef")} 注入，值由 application.yml 配置。 */
    @Bean("modelRef")
    public String modelRef() {
        return config.getModelRef();
    }

    /**
     * 装配全七层 ReActAgent。
     *
     * <p>每个 {@code @Qualifier} 指向 ch04-ch10 提供的具体 {@link MiddlewareBase} Bean。
     * 当某层在 {@link FullHarnessConfig} 中被关闭（{@code *Enabled = false}）时，
     * 该层 Middleware 不进入链——实现"逐层可升级/可裁剪"。
     *
     * @param modelRef      模型标识，如 {@code "dashscope:qwen-plus"}
     * @param toolkit       工具集（T 层 @Tool 注册入口）
     * @param safeGuard     G 层总入口（Input/Output/ToolPolicy 三检查点）
     * @param workingMemory C 层工作记忆
     * @param episodicMemory C 层情景记忆
     * @param compactor     C 层上下文压缩
     * @param sandboxAdvisor E 层沙箱隔离（onActing 注入 sandbox.* 配置，包裹工具执行）
     * @param toolCalling   T 层工具调用分发
     * @param orchestrator  L 层 ReAct 编排
     * @param stepLimit     L 层步数熔断
     * @param validator     V 层嵌入式评判（运行时每步打分）
     * @param tracer        O 层链路追踪
     * @param costTracker   O 层成本归因
     */
    @Bean
    public ReActAgent harnessAgent(                         // ── ② 核心方法：七层 Middleware 装配 ──
            @Qualifier("modelRef") String modelRef,
            Toolkit toolkit,
            @Qualifier("safeGuard") MiddlewareBase safeGuard,
            @Qualifier("workingMemory") MiddlewareBase workingMemory,
            @Qualifier("episodicMemory") MiddlewareBase episodicMemory,
            @Qualifier("compactor") MiddlewareBase compactor,
            @Qualifier("sandboxAdvisor") MiddlewareBase sandboxAdvisor,
            @Qualifier("toolCalling") MiddlewareBase toolCalling,
            @Qualifier("orchestrator") MiddlewareBase orchestrator,
            @Qualifier("stepLimit") MiddlewareBase stepLimit,
            @Qualifier("validator") MiddlewareBase validator,
            @Qualifier("tracer") MiddlewareBase tracer,
            @Qualifier("costTracker") MiddlewareBase costTracker) {

        List<MiddlewareBase> chain = new ArrayList<>();     // ── ③ 按 FullHarnessConfig 开关动态裁剪 ──

        // ═══════ G 层：第一道防线（总入口，内部含 Input/Output/ToolPolicy） ═══════
        if (config.isGovernanceEnabled()) {
            chain.add(safeGuard);
        }

        // ═══════ C 层：为模型准备背景 ═══════
        if (config.isContextEnabled()) {
            chain.add(workingMemory);      // 工作记忆——最近 N 轮对话原文
            chain.add(episodicMemory);     // 情景记忆——向量检索历史相关任务
            chain.add(compactor);          // 上下文压缩——窗口溢出时自动摘要
        }

        // ═══════ E 层：执行环境（沙箱，注册在 T 之前——onActing 先于工具分发注入隔离配置） ═══════
        if (config.isExecutionEnabled()) {
            chain.add(sandboxAdvisor);     // 沙箱隔离——onActing 注入 sandbox.* 配置，doFinally 清理
        }

        // ═══════ T 层：声明可调用能力 ═══════
        if (config.isToolingEnabled()) {
            chain.add(toolCalling);        // 注册所有 @Tool Bean，处理工具调用请求
        }

        // ═══════ L 层：控制执行流程 ═══════
        if (config.isLifecycleEnabled()) {
            chain.add(orchestrator);       // ReAct——规划→执行→观察→收敛循环
            chain.add(stepLimit);          // 步数熔断——超出 maxSteps 自动中断
        }

        // ═══════ V 层：事后验证（运行时仅嵌入式评判） ═══════
        if (config.isVerificationEnabled()) {
            chain.add(validator);          // 嵌入式验证——每步输出质量评分
            // RegressionEvaluator 不进链：@Component 离线评测器（非 MiddlewareBase），
            // 由 CI/定时任务调用 evaluate(suiteId) 跑回归套件，对比基线检测劣化信号。
        }

        // ═══════ O 层：全程可观测 ═══════
        if (config.isObservabilityEnabled()) {
            chain.add(tracer);             // 链路追踪——sessionId 贯穿全链路
            chain.add(costTracker);        // 成本归因——追踪 Token 消耗和费用累计
        }

        log.info("[HarnessAssembly] 七层 Middleware 装配完成，链长={}", chain.size());

        return ReActAgent.builder()
                .name("codepilot-full-harness")
                .model(modelRef)
                .toolkit(toolkit)
                .middlewares(List.copyOf(chain))   // 注册顺序 = 执行语义：入站正向、出站逆向
                .build();
    }
}
