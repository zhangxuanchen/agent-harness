# CodePilot — Agent Harness 工程 配套代码仓库

> 《Agent Harness 工程：构建可靠智能体系统的工程方法》配套 Java 代码
>
> Spring AI 1.x + JDK 21 + Spring Boot 3.5+

## 项目概述

CodePilot 是《Agent Harness 工程》一书的配套代码仓库，展示了 **ETCLOVG 七层架构**的完整实现。通过逐层添加 Harness 能力，可观察 Agent 任务成功率从单一 Agent 基线逐层提升到全七层受控状态。

> **关于成功率数字的说明**：本书 Ch1 路线图表中给出的 42% → 82% 为**教学示意值**，用于直观展示七层 Harness 的相对贡献（E 层隔离沙箱贡献最大，T/C/L 层次之，O/G 层主要降低风险与成本而非提升主指标），并非本仓库在统一基准上的严格对照实验结果。本仓库尚未附带标准化评估数据集与可复现的逐层对比实验脚本。**核心论点"Harness 决定可靠性、模型决定上限"是基于公开证据得出的**：SWE-agent（Princeton）在 SWE-bench 上从 3.8% → 12.5%（纯 T 层 ACI 设计、未换模型，+229%）；LangChain "Deep Agents" 在 Terminal Bench 2.0 上从 52.8% → 66.5%（仅通过 Harness 改进、未换模型权重，+26%）。读者应根据自己的场景通过真实评估获得量化结论。

## Book 章节与 CodePilot 映射

### 第一部分：为什么需要 Harness（理论章节，无代码）

| Book 章节 | 标题 | CodePilot 模块 |
|-----------|------|---------------|
| [chapter-01](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-01-agent-工程全景与前置知识.md) | Agent 工程全景与前置知识 | — (理论基础) |
| [chapter-02](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-02-重新定义Agent-从聊天到自主.md) | 重新定义 Agent | — (自主度分级、PDA 循环) |
| [chapter-03](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-03-agent-行为模式-设计模式实战问题地图.md) | Agent 行为模式 | — (故障模式分析、框架引入) |

### 第二部分：ETCLOVG 七层施工（核心代码实现）

| Book 章节 | 标题 | CodePilot 模块 | ETCLOVG 层 |
|-----------|------|---------------|-----------|
| [chapter-04](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-04-E-执行环境与沙箱.md) | E · 执行环境与沙箱 | [ch04-sandbox](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch04-sandbox) | **E** 执行环境 |
| [chapter-05](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-05-T-工具接口与协议.md) | T · 工具接口与协议 | [ch05-tools](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools) | **T** 工具接口 |
| [chapter-06](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-06-C-上下文与记忆.md) | C · 上下文与记忆 | [ch06-memory](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch06-memory) | **C** 上下文记忆 |
| [chapter-07](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-07-L-生命周期与编排.md) | L · 生命周期与编排 | [ch07-orchestration](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch07-orchestration) | **L** 生命周期编排 |
| [chapter-08](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-08-O-可观测性.md) | O · 可观测性 | [ch08-observability](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch08-observability) | **O** 可观测性 |
| [chapter-09](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-09-V-验证与评估.md) | V · 验证与评估 | [ch09-evaluation](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch09-evaluation) | **V** 验证评估 |
| [chapter-10](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-10-G-治理与安全.md) | G · 治理与安全 | [ch10-governance](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch10-governance) | **G** 治理安全 |
| [chapter-11](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-11-ETCLOVG回顾.md) | ETCLOVG 回顾 | — (七层协同回顾) |

### 第三部分：进阶工程专题（书籍有内容，代码待实现）

| Book 章节 | 标题 | 状态 |
|-----------|------|------|
| [chapter-12](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-12-模型层工程.md) | 模型层工程 | 🔄 待实现 |
| [chapter-13](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-13-数据知识与工具制造.md) | 数据知识与工具制造 | 🔄 待实现 |
| [chapter-14](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-14-规划推理与决策.md) | 规划推理与决策 | 🔄 待实现 |
| [chapter-15](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-15-多Agent系统与协作.md) | 多 Agent 系统与协作 | 🔄 待实现 |
| [chapter-16](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-16-Agent-MLOps与评估流水线.md) | Agent MLOps 与评估流水线 | 🔄 待实现 |
| [chapter-17](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-17-生产监控可靠性与成本.md) | 生产监控可靠性与成本 | 🔄 待实现 |
| [chapter-18](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-18-案例研究.md) | 案例研究 | 📖 纯案例 |
| [chapter-19](file:///Users/zxc/Documents/ai/agent-harness/book/chapter-19-开放问题与未来.md) | 开放问题与未来 | 📖 展望章节 |

### 附录

| 附录 | 标题 |
|------|------|
| [appendix-A](file:///Users/zxc/Documents/ai/agent-harness/book/appendix-A-Agent故障诊断速查表.md) | Agent 故障诊断速查表 |
| [appendix-B](file:///Users/zxc/Documents/ai/agent-harness/book/appendix-B-Agent构建方案速查表.md) | Agent 构建方案速查表 |
| [appendix-C](file:///Users/zxc/Documents/ai/agent-harness/book/appendix-C-Harness结构完整度速查表.md) | Harness 结构完整度速查表 |

## 模块详情

### codepilot-core（共享核心）

共享接口和基础设施，所有模块的依赖基础。

| 文件 | 说明 |
|------|------|
| [Layer.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/codepilot-core/src/main/java/io/etclovg/codepilot/core/Layer.java) | ETCLOVG 七层枚举 |

### ch04-sandbox（E 层 · 执行环境）

Docker 沙箱隔离、资源限制、网络策略。

| 文件 | 说明 |
|------|------|
| [Ch04Application.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch04-sandbox/src/main/java/io/etclovg/codepilot/sandbox/Ch04Application.java) | 应用入口 |
| [SandboxAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch04-sandbox/src/main/java/io/etclovg/codepilot/sandbox/SandboxAdvisor.java) | 沙箱隔离 Advisor |
| [SandboxConfig.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch04-sandbox/src/main/java/io/etclovg/codepilot/sandbox/SandboxConfig.java) | 沙箱配置 |

### ch05-tools（T 层 · 工具接口）

ACID 工具描述、语义路由、策略治理。

| 文件 | 说明 |
|------|------|
| [Ch05Application.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools/src/main/java/io/etclovg/codepilot/tools/Ch05Application.java) | 应用入口 |
| [SemanticToolRouter.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools/src/main/java/io/etclovg/codepilot/tools/SemanticToolRouter.java) | 语义工具路由 |
| [ToolPolicy.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools/src/main/java/io/etclovg/codepilot/tools/ToolPolicy.java) | 工具策略注解 |
| [ToolPolicyAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools/src/main/java/io/etclovg/codepilot/tools/ToolPolicyAdvisor.java) | 策略治理 Advisor |
| [ToolValidationAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools/src/main/java/io/etclovg/codepilot/tools/ToolValidationAdvisor.java) | 工具验证 Advisor |
| [StructuredErrorHandler.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools/src/main/java/io/etclovg/codepilot/tools/StructuredErrorHandler.java) | 结构化错误处理 |
| [ToolPinningService.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch05-tools/src/main/java/io/etclovg/codepilot/tools/ToolPinningService.java) | 工具固定服务 |

### ch06-memory（C 层 · 上下文记忆）

三层记忆架构、五区预算、KV-cache。

| 文件 | 说明 |
|------|------|
| [Ch06Application.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch06-memory/src/main/java/io/etclovg/codepilot/memory/Ch06Application.java) | 应用入口 |
| [MemoryManager.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch06-memory/src/main/java/io/etclovg/codepilot/memory/MemoryManager.java) | 三层记忆管理器 |
| [ContextBudgetAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch06-memory/src/main/java/io/etclovg/codepilot/memory/ContextBudgetAdvisor.java) | 上下文预算 Advisor |
| [ContextCompactor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch06-memory/src/main/java/io/etclovg/codepilot/memory/ContextCompactor.java) | 上下文压缩器 |

### ch07-orchestration（L 层 · 生命周期编排）

ReAct 循环、状态机、循环检测。

| 文件 | 说明 |
|------|------|
| [Ch07Application.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch07-orchestration/src/main/java/io/etclovg/codepilot/orchestration/Ch07Application.java) | 应用入口 |
| [ReActOrchestrator.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch07-orchestration/src/main/java/io/etclovg/codepilot/orchestration/ReActOrchestrator.java) | ReAct 编排器（四道防护） |
| [StateMachineManager.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch07-orchestration/src/main/java/io/etclovg/codepilot/orchestration/StateMachineManager.java) | 状态机管理器 |
| [LoopDetectionAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch07-orchestration/src/main/java/io/etclovg/codepilot/orchestration/LoopDetectionAdvisor.java) | 循环检测 Advisor |
| [GoalAnchorAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch07-orchestration/src/main/java/io/etclovg/codepilot/orchestration/GoalAnchorAdvisor.java) | 目标锚定 Advisor |
| [PipeReActAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch07-orchestration/src/main/java/io/etclovg/codepilot/orchestration/PipeReActAdvisor.java) | Pipeline+ReAct 混合编排 |

### ch08-observability（O 层 · 可观测性）

结构化日志、成本归因、燃烧率。

| 文件 | 说明 |
|------|------|
| [Ch08Application.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch08-observability/src/main/java/io/etclovg/codepilot/observability/Ch08Application.java) | 应用入口 |
| [EventLogAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch08-observability/src/main/java/io/etclovg/codepilot/observability/EventLogAdvisor.java) | JSONL 事件日志 |
| [CostTracker.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch08-observability/src/main/java/io/etclovg/codepilot/observability/CostTracker.java) | 多维度成本追踪 |
| [BurnRateCalculator.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch08-observability/src/main/java/io/etclovg/codepilot/observability/BurnRateCalculator.java) | 燃烧率计算器 |

### ch09-evaluation（V 层 · 验证评估）

EDD 五阶段、LLM-as-Judge、回归检测。

| 文件 | 说明 |
|------|------|
| [Ch09Application.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch09-evaluation/src/main/java/io/etclovg/codepilot/evaluation/Ch09Application.java) | 应用入口 |
| [EvaluationAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch09-evaluation/src/main/java/io/etclovg/codepilot/evaluation/EvaluationAdvisor.java) | EDD 评估 Advisor |
| [LLMJudgeAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch09-evaluation/src/main/java/io/etclovg/codepilot/evaluation/LLMJudgeAdvisor.java) | LLM-as-Judge |
| [RegressionRunner.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch09-evaluation/src/main/java/io/etclovg/codepilot/evaluation/RegressionRunner.java) | 回归测试运行器 |

### ch10-governance（G 层 · 治理安全）

四检查点防御、YAML 宪法、审计日志。

| 文件 | 说明 |
|------|------|
| [Ch10Application.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch10-governance/src/main/java/io/etclovg/codepilot/governance/Ch10Application.java) | 应用入口 |
| [InputGuardAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch10-governance/src/main/java/io/etclovg/codepilot/governance/InputGuardAdvisor.java) | 输入防护 Advisor |
| [ConstitutionValidator.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch10-governance/src/main/java/io/etclovg/codepilot/governance/ConstitutionValidator.java) | 宪法验证器 |
| [AuditLogAdvisor.java](file:///Users/zxc/Documents/ai/agent-harness/codepilot/ch10-governance/src/main/java/io/etclovg/codepilot/governance/AuditLogAdvisor.java) | 审计日志 Advisor |

## 环境要求

- **JDK 21+**
- **Maven 3.9+**
- **Docker**（仅 Ch04 沙箱模块需要）
- **可选**：PostgreSQL + pgvector（Ch06 语义记忆）、Redis（Ch06 KV-cache）

## 快速开始

### 1. 编译全项目

```bash
cd codepilot
mvn clean package -DskipTests
```

### 2. 运行各层示例

每个模块都有独立的 Spring Boot 应用入口：

```bash
# Ch04 · E 层 — Docker 沙箱（需 Docker 环境）
cd ch04-sandbox && mvn spring-boot:run

# Ch05 · T 层 — 工具接口
cd ch05-tools && mvn spring-boot:run

# Ch06 · C 层 — 上下文记忆（需 API Key）
cd ch06-memory && mvn spring-boot:run

# Ch07 · L 层 — 生命周期编排
cd ch07-orchestration && mvn spring-boot:run

# Ch08 · O 层 — 可观测性
cd ch08-observability && mvn spring-boot:run

# Ch09 · V 层 — 验证评估
cd ch09-evaluation && mvn spring-boot:run

# Ch10 · G 层 — 治理安全
cd ch10-governance && mvn spring-boot:run
```

## 架构说明

### ETCLOVG 七层架构

```
┌─────────────────────────────────────────────────────┐
│ G 治理安全 (Governance)                             │
│   四检查点防御 · YAML 宪法 · 审计日志               │
├─────────────────────────────────────────────────────┤
│ V 验证评估 (Verification)                           │
│   EDD 五阶段 · LLM-as-Judge · 回归检测             │
├─────────────────────────────────────────────────────┤
│ O 可观测性 (Observability)                          │
│   JSONL 日志 · 成本归因 · 燃烧率                    │
├─────────────────────────────────────────────────────┤
│ L 生命周期编排 (Lifecycle)                          │
│   ReAct 循环 · 状态机 · 循环检测 · 目标锚定         │
├─────────────────────────────────────────────────────┤
│ C 上下文记忆 (Context/Memory)                       │
│   三层记忆 · 五区预算 · KV-cache · 压缩策略         │
├─────────────────────────────────────────────────────┤
│ T 工具接口 (Tool Interface)                         │
│   ACID 描述 · 语义路由 · 策略治理 · 版本控制       │
├─────────────────────────────────────────────────────┤
│ E 执行环境 (Execution Environment)                  │
│   Docker 沙箱 · 资源限制 · 网络隔离 · 快照回滚     │
└─────────────────────────────────────────────────────┘
```

### 代码示例

以 Ch07 ReAct 编排器为例：

```java
// ReActOrchestrator.java — 四道防护实现
@Override
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    // 1. 步骤限制：防止无限循环
    if (!checkStepLimit(taskId)) {
        return buildTerminatedResponse("达到步骤上限");
    }
    
    // 2. 重复检测：SHA-256 哈希比较
    if (detectDuplicate(taskId, callSignature)) {
        context.put("react.duplicate_warning", "你已重复调用同一工具");
    }
    
    // 3. 超时控制：单步和总任务双层超时
    if (isTotalTimeout(taskId)) {
        return buildTerminatedResponse("总超时");
    }
    
    // 4. 熔断器：连续 N 次失败后进入 OPEN 状态
    if (isCircuitOpen(taskId)) {
        return buildTerminatedResponse("熔断器已打开");
    }
    
    return chain.nextCall(request);
}
```

## 配置说明

### API Key 配置

部分模块需要配置 AI 服务提供商的 API Key：

```yaml
# application.yml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
```

### 可选服务

- **PostgreSQL + pgvector**：用于 Ch06 语义记忆的向量存储
- **Redis**：用于 Ch06 KV-cache 稳定前缀
- **Docker**：用于 Ch04 沙箱隔离

## 故障排除

1. **编译失败**
   - 确保使用 JDK 21+
   - 运行 `mvn clean compile` 重新编译

2. **运行时错误：API Key 未配置**
   - 设置环境变量 `export OPENAI_API_KEY=your-key`

3. **Docker 相关错误（Ch04）**
   - 确保 Docker 已安装并运行

## 许可证

Apache 2.0 — 详见 LICENSE 文件。
