# CodePilot — Agent Harness 工程 配套代码仓库

> 《Agent Harness 工程：构建可靠智能体系统的工程方法》配套 Java 代码
>
> JDK 21 + Maven + AgentScope 2.x（agentscope-core / agentscope-harness）+ Spring Boot 装配基座

## 项目概述

CodePilot 是全书的贯穿案例：一个编码辅助 Agent，从裸模型开始，按章节逐层叠加 Harness 能力，直到 ETCLOVG 七层完整受控。每个章节对应一个独立 Maven 模块，模块内的类名和 API 与书稿中的代码示例一致。

代码分两类：

- **框架类**：AgentScope 2.x 提供（`io.agentscope` 包），如 Agent、Middleware、RuntimeContext
- **教学实现类**：本仓库自研（`io.etclovg.codepilot` 包），在注释中标注"配套仓库教学实现，非框架内置"，用于演示每层 Harness 的工程做法

> **关于成功率数字的说明**：书稿第 1 章路线图中的 42% → 82% 为教学示意值，用于直观展示七层 Harness 的相对贡献（E 层隔离沙箱贡献最大，T/C/L 层次之，O/G 层主要降低风险与成本而非提升主指标），并非本仓库在统一基准上的严格对照实验结果。核心论点"Harness 决定可靠性、模型决定上限"有公开证据支撑：SWE-agent（Princeton）在 SWE-bench 上从 3.8% → 12.5%（纯 T 层 ACI 设计、未换模型，+229%）；LangChain "Deep Agents" 在 Terminal Bench 2.0 上从 52.8% → 66.5%（仅 Harness 改进、未换模型权重，+26%）。

## 环境要求

- **JDK 21+**
- **Maven 3.9+**
- **Docker**（仅 ch04-sandbox 的容器沙箱演示需要）
- **可选**：模型服务 API Key（ch01/ch06/ch09 等需要真实 LLM 的演示，通过 `agentscope-extensions-model-dashscope` 等模型扩展接入）

## 快速开始

```bash
# 编译全部模块
cd codepilot
mvn clean package -DskipTests

# 跑某个章节模块的测试（推荐的学习方式：测试即可运行的示例）
mvn test -pl ch08-observability
mvn test -pl ch10-governance

# 跑带 main 方法的演示类
mvn -pl ch04-sandbox exec:java -Dexec.mainClass=io.etclovg.codepilot.sandbox.ResourceLimitsDemo
mvn -pl ch15-multiagent exec:java -Dexec.mainClass=io.etclovg.codepilot.multiagent.AgentCardDemo

# 完整 Spring Boot 装配（七层一次性注册）
mvn -pl codepilot-starter spring-boot:run
```

各模块的主要演示入口：

| 模块 | 演示入口 |
|------|----------|
| ch01-foundation | `AgentAutonomyLevels`（自主度分级）、`QuickstartAgent`（第一个 Agent） |
| ch03-behavior | `CodePilotSkeleton`（带故障检测的骨架） |
| ch04-sandbox | `ResourceLimitsDemo`、`DockerSandboxDemo`、`E2BSandboxDemo`、`DaytonaSandboxDemo` |
| ch05-tools | `McpEchoServer`（本地 MCP 服务） |
| ch15-multiagent | `AgentCardDemo`（A2A Agent 卡片） |
| codepilot-starter | `CodepilotApplication`（Spring Boot 完整装配） |

## 章节与模块映射

### 共享模块

| 模块 | 内容 |
|------|------|
| [codepilot-core](codepilot-core) | 七层枚举 `Layer`、所有层间中间件的基类 `AbstractLayerMiddleware` |
| [codepilot-starter](codepilot-starter) | Spring Boot 启动器，七层 Middleware 一次性注册的完整装配 |

### Part 1 基础篇

| 书稿章节 | 模块 | 核心类 |
|----------|------|--------|
| 第 1 章 Agent 工程全景 | [ch01-foundation](ch01-foundation) | `QuickstartAgent`、`PdaLoopService`（PDA 闭环）、`HarnessGuard`、`FaultDetectionMiddleware`（PDA 每步故障检测）、`StepLimitMiddleware`、`UsageLimitMiddleware` |
| 第 2 章 重新定义 Agent | [ch02-definition](ch02-definition) | `GoalAnchorMiddleware`（目标锚定）、`SafeGuardMiddleware`、`RefusalGuard`、`LoopDetectionMiddleware`、`ToolCallingMiddleware` |
| 第 3 章 行为诊断 | [ch03-behavior](ch03-behavior) | `AgentStateManager`（状态外置）、`FaultDetector`、`VerificationMiddleware`、`TokenCostTracker`、`EventBus`、`CodePilotSkeleton` |

### Part 2 ETCLOVG 七层施工

| 书稿章节 | 模块 | 层 | 核心类 |
|----------|------|----|--------|
| 第 4 章 E 执行环境与沙箱 | [ch04-sandbox](ch04-sandbox) | **E** | `SandboxPool`（预热池）、`DockerSandboxClient`/`E2BSandboxClient`/`DaytonaSandboxClient`/`KubernetesSandboxClient`（四种方案）、`ResourceLimits`（八维硬限制）、`NetworkPolicy`、`OverlayFSSnapshotManager`（三层文件系统）、`TimeoutMiddleware` |
| 第 5 章 T 工具接口与协议 | [ch05-tools](ch05-tools) | **T** | `SemanticToolRouter`（语义路由）、`ToolPolicyAdvisor`（白名单/频率/配额）、`ToolValidationAdvisor`、`DynamicToolRegistry`（动态发现）、`ParallelToolExecutor`、`SagaCoordinator`（可回滚）、`McpEchoServer`、`ToolVersionManager` |
| 第 6 章 C 上下文记忆 | [ch06-memory](ch06-memory) | **C** | `FiveZoneBudgetAllocator`（五区预算）、`WorkingMemoryManager`（滑动窗口压缩）、`ContextCompactor`（结构化/提取式/生成式压缩）、`CacheAwareSystemPromptBuilder`/`CacheHitRateDiagnoser`（KV-cache）、`EpisodicMemoryMiddleware`、`SemanticMemoryDistiller`、`InternalRAGService`、`DriftDetector`（腐烂/漂移）、`SessionResumeValidator`、`HandoffContextBuilder` |
| 第 7 章 L 生命周期编排 | [ch07-orchestration](ch07-orchestration) | **L** | `ReActOrchestrator`（步数/重复/超时/熔断四道防护）、`PipelineOrchestrator`、`Blackboard`、`SupervisorAgent`（层级）、`MeshOrchestrator`（网状）、`PipeReActOrchestrator`（混合编排）、`PlanExecuteVerifyController`、`StateMachineManager`、`CheckpointManager`（断点续传） |
| 第 8 章 O 可观测性 | [ch08-observability](ch08-observability) | **O** | `TracerMiddleware`/`TracePropagator`（Agent span）、`ObservabilityMiddleware`（旁路异步发送）、`EventLogRecorder`（JSONL 事件日志）、`CostTracker`/`CostAttributionMiddleware`（五标签归因）、`BurnRateCalculator`（燃烧率熔断）、`AssumptionDriftDetector`（假设漂移）、`ReplayEngine`（时间旅行回放）、`HarnessAssumptionRegistry`、`PiiMaskingFilter` |
| 第 9 章 V 验证与评估 | [ch09-evaluation](ch09-evaluation) | **V** | `EvaluationAdvisor`（五阶段状态机）、`ReadinessCheckAdvisor` + `SolvabilityEstimator`（就绪检查/可解性预估）、`EmbeddedValidationAdvisor`（嵌入式评判）、`RegressionRunner`（回归门禁）、`LLMJudgeAdvisor`（LLM-as-Judge）、`JudgeDriftDetector`（Judge 漂移）、`ShadowTrafficRouter`（影子流量）、`ABTestRunner` |
| 第 10 章 G 治理安全 | [ch10-governance](ch10-governance) | **G** | `InputGuardAdvisor`/`ToolGuardAdvisor`/`OutputGuardAdvisor`（四检查点）、`SessionMonitor`、`ConstitutionValidator` + [constitution.yml](ch10-governance/src/main/resources/constitution.yml)（声明式宪法）、`AuditLogAdvisor`（WORM 审计）、`RiskAdaptiveGovernor`（风险自适应）、`SupplyChainGuard`（供应链）、`SecurityCheckpoint`、`ShadowTrafficCollector` |
| 第 11 章 ETCLOVG 全景组装 | [ch11-etcclovg](ch11-etcclovg) | 全部 | `HarnessAssemblyConfig`（七层 Middleware 一次性注册）、`FullHarnessConfig`、`RegressionEvaluator` |

### Part 3 进阶篇

| 书稿章节 | 模块 | 核心类 |
|----------|------|--------|
| 第 12 章 模型层工程 | [ch12-model](ch12-model) | `LayeredModelRouter`/`ModelRouter`（三层路由）、`ModelFallbackService`/`CascadeMiddleware`（优雅降级）、`ModelSelectionTriangle`（能力×成本×延迟）、`TokenOptimizer`、`PrefixCacheMonitorMiddleware`、`ModelEnsemble` |
| 第 13 章 数据与工具制造 | [ch13-data](ch13-data) | `DataCleaner`、`AstSafetyScanner`（生成代码安全扫描）、`ApprovalGateway`（工具制造审批门）、`BiasGuard` |
| 第 14 章 规划推理与决策 | [ch14-planning](ch14-planning) | `ReasoningRouter`（ReAct/Plan-Execute/ToT/ReWOO 四范式路由）、`ReActMiddleware`、`PlanExecuteMiddleware`、`ToTMiddleware`、`ReWOOMiddleware`、`DynamicUpgrader`（动态升级）、`ThreeTierFallback`（三级回退）、`HumanHandoffMiddleware`（人机交接）、`PlanPreEvaluator`（计划预评估） |
| 第 15 章 多 Agent 协作 | [ch15-multiagent](ch15-multiagent) | `DagDecomposer`（任务分解 DAG）、`TopologyRouter`（四种拓扑）、`WorkerCircuitBreaker`（级联防护）、`ConsistencyVerifier` + `SchemaValidator` + `LlmJudge`（四阶段一致性）、`MastFailureDetector`/`MastFailureMode`（14 种 MAST 失败模式）、`ToolPermissionGate`（工具权限闸门）、`GovernedMemoryStore`（共享记忆治理）、`SessionManager`（Agent/Session 分离）、`OtelAgentConfig`（OTel 集成） |

### Part 4 生产化

| 书稿章节 | 模块 | 核心类 |
|----------|------|--------|
| 第 16 章 Agent MLOps | [ch16-mlops](ch16-mlops) | `GatePipelineOrchestrator`（四阶段门禁：Human/Eval/Perf/Policy）、`AgentBundleBuilder`/`AgentBundleRegistry`（构建工件）、`EvalWorkerPool`/`RateLimitAwareScheduler`（评估并行调度）、`CanaryController`/`ShadowTrafficRouter`（金丝雀/影子）、`CompensationExecutor`（回滚补偿）、`ModelUpdateManager`（模型平滑过渡）、`IncidentPostmortemTemplate`（五步复盘）、`RoadmapAssessor`（四阶段路线图） |
| 第 17 章 生产监控/可靠性/成本 | [ch17-reliability](ch17-reliability) | `FourLayerBudgetEnforcer`（组织→任务→会话→节点四层硬预算）、`ThreeTierControlLoop`（快中慢三回路）、`ThreeTierSLOCalculator`（功能/技术/业务分层 SLO）、`BurnRateMonitor`、`CostHeatmapGenerator`、`TieredLogManager`、`ManagerQuadrantCalculator`（管理者四象限）、`HotStorageClient`/`WarmStorageClient`/`ColdStorageClient`（分层存储） |

### Part 5 实战与展望

| 书稿章节 | 模块 | 核心类 |
|----------|------|--------|
| 第 18 章 案例研究 | [ch18-case-study](ch18-case-study) | `CodePilotFullHarness`（CodePilot 终版全景）、`SandboxMiddleware`、`ToolValidationMiddleware`、`EvaluationMiddleware` |
| 第 19 章 开放问题 | [ch19-future](ch19-future) | `HarnessComplexityAnalyzer`（最优复杂度）、`MetaHarnessGovernance`（Harness 自治治理）、`ShadowModeEvaluator`/`SelfImprovementProposal`（自改进安全）、`HarnessInvestmentShift`（投资重心演化）、`ConfigurationBundleManager`（可移植配置） |

## 测试

测试即可运行的文档，覆盖各层核心机制：

| 模块 | 测试类 | 验证内容 |
|------|--------|----------|
| ch01-foundation | `Ch01FoundationTest` | PDA 闭环与故障模式检测 |
| ch04-sandbox | `SandboxPoolTest` | 预热池复用与隔离 |
| ch05-tools | `Ch05AgentScopeToolApiTest`、`McpIntegrationTest`、`SemanticToolRouterTest`、`ToolPolicyAdvisorTest`、`ToolValidationAdvisorTest` | 工具 API、MCP 集成、路由与策略 |
| ch06-memory | `WorkingMemoryManagerTest` | 滑动窗口压缩 |
| ch07-orchestration | `ReActOrchestratorTest`、`LoopDetectionAdvisorTest` | 循环收敛与重复检测 |
| ch08-observability | `CostTrackerTest`、`AssumptionDriftDetectorTest` | 成本归因与假设漂移 |
| ch09-evaluation | `EvaluationAdvisorTest`、`ReadinessCheckAdvisorTest` | 评估状态机与就绪检查 |
| ch10-governance | `InputGuardAdvisorTest`、`ToolGuardAdvisorTest`、`ConstitutionValidatorTest`、`AuditLogAdvisorTest`、`SecurityCheckpointTest`、`SupplyChainGuardTest`、`RiskAdaptiveGovernorTest`、`ShadowTrafficCollectorTest` | 四检查点、宪法、审计、供应链、自适应治理 |
| ch11-etcclovg | `HarnessAssemblyConfigTest` | 七层完整装配 |
| ch16-mlops | `GatePipelineOrchestratorTest` | 四阶段门禁流水线 |
| ch17-reliability | `FourLayerBudgetEnforcerTest`、`Ch17BudgetEnforcementIntegrationTest` | 四层预算强制 |

## 架构说明

### ETCLOVG 七层与 Middleware 执行顺序

第 11 章给出七层 Middleware 的注册顺序（G → C → E → T → L → V → O）：治理最先进场拦截，观测最后进场兜底记录。

```
请求 → G(治理/审计) → C(上下文/预算) → E(沙箱/资源) → T(工具/策略)
     → L(编排/循环) → V(评估/门禁) → O(观测/回放) → 模型
```

### 层间中间件约定

所有自研 Middleware 继承 `codepilot-core` 的 `AbstractLayerMiddleware`（配套仓库教学实现，非框架内置），不匿名实现框架底层接口。中间件签名以 `onActing(Agent agent, RuntimeContext rc, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next)` 为准；工具名等运行时信息从 `RuntimeContext` 取（`rc.getExtra().getOrDefault("tool.name", "unknown")`），不从 `ActingInput` 取。链路追踪使用 OpenTelemetry 标准入口 `GlobalOpenTelemetry.getTracer()`。

## 配置说明

需要真实 LLM 的演示通过 AgentScope 模型扩展接入（如 `agentscope-extensions-model-dashscope`），API Key 从环境变量读取：

```bash
export DASHSCOPE_API_KEY=your-key
# 或
export OPENAI_API_KEY=your-key
```

ch10 的治理策略集中在声明式文件 [constitution.yml](ch10-governance/src/main/resources/constitution.yml)，改规则不需要改代码。

## 故障排除

1. **编译失败**：确认 JDK 21+（`java -version`），执行 `mvn clean install -DskipTests` 先安装 codepilot-core
2. **运行时报 API Key 未配置**：设置对应模型服务的环境变量
3. **ch04 Docker 相关错误**：确认 Docker 已启动；无需容器演示时可只跑 `SandboxPoolTest` 和资源限制类演示
4. **模块间找不到依赖**：先在 codepilot 根目录执行一次 `mvn install -DskipTests`

## 许可证

Apache 2.0，详见 [LICENSE](LICENSE)。
