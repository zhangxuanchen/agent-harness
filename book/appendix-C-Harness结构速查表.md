# 附录 C — Harness 结构速查表

> **三表定位**：
> - **附录 A（故障诊断）**：出问题后怎么修——按症状反查根因
> - **附录 B（构建方案）**：从零开始怎么建——按构建顺序正向设计
> - **附录 C（完整度审计）**：建好了能不能上线——按门禁标准逐项核验
>
> **使用时机**：项目进入工程试点阶段（阶段 2）前，先用 C.4 跑一遍七层审计；阶段 3 规模推广前，用 C.3 上线门禁做 go/no-go 决策；每次跨层变更后，用 C.5 回归矩阵确认没有引入相邻层缺陷。

---

## C.1 使用方法

```
项目阶段              必跑审计项              通过标准
─────────────────────────────────────────────────────────
阶段 1 · Demo         C.4 七层审计 P0 项       所有 P0 ✓
阶段 2 · 工程试点     C.3 上线门禁             所有 P0 ✓ + P1 ≥ 80%
阶段 3 · 规模推广     C.2 成熟度评估           达到 L2 生产级
阶段 4 · 持续演进     C.6 运维就绪             SLO + Runbook + On-call 全到位
跨层变更              C.5 耦合回归矩阵         受影响层全部回归通过
```

**核验原则**：每一项检查都给出 ① 不做的代价 ② 验证方法 ③ 量化阈值。无法量化即无法核验——如果某项无法回答"怎么算通过"，它就不在本表内。

**Demo 阶段精简原则**：阶段 1（Demo/PoC）仅需完成 C.4 七层审计中的 P0 项（约 15 项），不需要全部 60+ 项检查。完整检查仅在阶段 2（工程试点）及之后执行。标记为 🔴 骨架的代码仅用于结构参考，不可直接用于生产环境。

---

## C.2 Harness 成熟度模型（L0-L3）

> 用四个等级评估你的 Harness 工程化水位。每级有明确的进入条件和典型特征，定位你当前在哪一级、距离下一级还差什么。

| 等级 | 名称 | 典型特征 | 可靠性水位 | 典型场景 |
|------|------|---------|-----------|---------|
| **L0** | 裸模型 | 直接调 API，无任何 Harness 层 | 42% 端到端成功率 | Demo / Hackathon |
| **L1** | 基础防护 | T 层工具描述 + L 层步数限制 + G 层基础过滤 | 60-70% | 内部试点、PoC |
| **L2** | 生产就绪 | E+V+G 三层生存三角 + O 层可观测 + C 层记忆 | 80-85% | 生产环境小规模 |
| **L3** | 持续优化 | 四层预算 + 数据飞轮 + Canary + 模型路由 | 90%+ | 规模化生产 |

### 各级进入条件

#### L0 → L1（Demo 转试点）

必须完成：
- [ ] T 层：所有工具描述包含五要素（名称/参数/返回值/错误模式/示例）
- [ ] L 层：ReAct 循环有步数硬上限（≤30）
- [ ] L 层：连续 3 步重复 thought+tool_call 强制跳出
- [ ] G 层：输入正则过滤（<1ms，覆盖 OWASP Top 10 注入模式）

典型耗时：1-2 周。

#### L1 → L2（试点转生产）⭐ 最关键的跨越

必须完成（即 C.3 上线门禁的全部 P0 项）：
- [ ] E 层：Docker 加固沙箱（非 root + cap-drop + seccomp + 资源限额）
- [ ] V 层：100+ 任务回归评估集，故障率 > 10% 阻断部署
- [ ] G 层：四检查点全覆盖 + YAML 声明式策略
- [ ] O 层：五标签成本归因 + P99 异常检测
- [ ] L 层：per-session 成本上限 + 熔断器
- [ ] C 层：上下文压缩机制 + KV-cache 稳定前缀

典型耗时：4-8 周。这是投入最大（40-50%）的跨越，也是 Demo 到产品的质变点。

#### L2 → L3（生产转规模化）

必须完成：
- [ ] O 层：Prometheus + Grafana + Loki 完整监控栈
- [ ] Ch17：四层硬预算体系（组织/用户/任务/节点）
- [ ] Ch16：四阶段门禁 CI/CD + Canary 渐进发布
- [ ] Ch12：三层模型路由 + 模型漂移检测
- [ ] Ch9：影子流量 A/B 测试 + 统计显著性检验
- [ ] C.6 运维就绪：SLO 定义 + Runbook + On-call 轮值

典型耗时：2-4 月。

---

## C.3 上线门禁（Production Readiness Gate）

> **go/no-go 决策清单**。所有 P0 项必须 ✓，P1 项 ≥ 80% ✓，方可进入生产环境。P0 = 上线即事故的风险，P1 = 上线后很快会出事的风险。

### P0 门禁项（全部必须通过）

| # | 层 | 检查项 | 不做的代价 | 验证方法 | 章节 |
|---|---|--------|-----------|---------|------|
| G1 | G | 输入检查点：L1 正则过滤覆盖 OWASP Top 10 注入模式 | 提示注入导致数据泄露/越权 | 用 AgentDojo 100 条注入用例测试，拦截率 ≥ 95% | Ch10 KP 10.2.2 分级安全管线 |
| G2 | G | 工具调用检查点：危险操作白名单 + 人工审批 | Agent 执行 `DROP TABLE`/`rm -rf` | grep 所有 @Tool，危险操作必须有 `requiresApproval=true` | Ch10 KP 10.2.1 检查点威胁与防护 |
| G3 | G | 输出检查点：PII 脱敏（正则 + 掩码） | 输出用户密码/身份证号 | 注入 50 条 PII 测试用例，泄露率 = 0 | Ch10 KP 10.2.1 检查点威胁与防护 |
| E1 | E | 沙箱隔离：非 root + cap-drop=ALL + seccomp | Agent 破坏宿主机文件系统 | `docker inspect` 验证容器参数 | Ch4 §4.2 |
| E2 | E | 资源配额：CPU/内存/磁盘/超时 | 死循环脚本打满节点 | 模拟死循环，30s 内被终止 | Ch4 KP 4.4.1 资源配额 |
| E3 | E | 文件系统隔离：不与宿主机共享写目录 | Agent 覆盖生产配置文件 | 沙箱内尝试写 `/etc/`，应失败 | Ch4 §4.2 隔离技术选型 |
| L1 | L | 步数硬上限（默认 ≤30） | $47K 循环事故重演 | 配置检查 + 压测触发上限 | Ch7 KP 7.1.2 循环收敛性保证 |
| L2 | L | per-session 成本上限 + 熔断 | 单用户无限循环拖垮组织预算 | 模拟超限，应自动终止 | Ch17 KP 17.3.1 预算熔断 |
| L3 | L | 连续重复检测（N=3 强制跳出） | Agent 在空结果上死循环 | 模拟连续 3 次相同 tool_call | Ch7 KP 7.1.2 循环收敛性保证 |
| V1 | V | 100+ 任务回归评估集 | 改一处坏三处，上线即故障 | 评估集故障率 ≤ 10% | Ch9 KP 9.1.2 评估体系三层 |
| V2 | V | CI 门禁：故障率 > 10% 阻断部署 | 带病上线 | 触发一次回归，验证门禁生效 | Ch16 KP 16.1.1 上线门禁 |
| O1 | O | 成本归因：五标签（user/session/task/model/tool） | 月底账单看不清钱花在哪 | 查询近 24h 成本，能按标签 GROUP BY | Ch8 KP 8.4.1 Token 成本追踪 |
| O2 | O | 事件日志：JSONL append-only + traceId | 出事后无法回放推理过程 | 抽查一条 trace，能完整还原 Agent 决策链 | Ch8 KP 8.5.1 日志与 trace 关联 |
| T1 | T | 工具描述五要素（名称/参数/返回值/错误/示例） | 55% → 92% 准确率的差距 | 逐个工具检查 description 字段 | Ch5 KP 5.1.1 四元组与五要素 |
| T2 | T | 结构化错误协议（errorCode/suggestion/retryable） | Agent 无法程序化自愈 | 触发一次工具错误，验证返回 JSON | Ch5 KP 5.5.3 超时重试与错误 |

### P1 门禁项（≥80% 必须通过）

| # | 层 | 检查项 | 不做的代价 | 验证方法 | 章节 |
|---|---|--------|-----------|---------|------|
| G4 | G | L2 分类器检测（<50ms，覆盖 15% 高级攻击） | 正则漏掉变体注入 | 用 AgentDojo 高级用例测试 | Ch10 KP 10.2.2 分级安全管线 |
| G5 | G | YAML 声明式策略 + Git 版控 | 策略散落代码，不可审计 | 检查策略文件是否在 Git 仓库 | Ch10 KP 10.3.1 策略即代码 |
| G6 | G | WORM 审计日志 + 决策链追溯 | 事故后无法归责 | 抽查一条决策，能追溯到 6 层归因 | Ch10 KP 10.3.2 审计日志 |
| E4 | E | 沙箱预热池（消除冷启动） | 高并发时 2-5s 延迟 | 压测 P95 启动延迟 < 100ms | Ch4 KP 4.5.1 预热池与复用 |
| E5 | E | 网络白名单（只允许清单内域名） | Agent 访问不该访问的服务 | 沙箱内 curl 非白名单域名，应失败 | Ch4 KP 4.4.2 网络白名单 |
| L4 | L | 检查点/恢复机制（崩溃后可续跑） | 跑 20 步崩溃从头开始 | 杀掉 Agent 进程，验证可从 checkpoint 恢复 | Ch7 KP 7.7.2 检查点设计 |
| L5 | L | 编排模式选型有书面记录 | 默认 ReAct 导致成本失控 | 检查选型决策树文档 | Ch7 KP 7.4.1 横向基准与决策树 |
| V3 | V | LLM-as-Judge 三招偏差治理 + Pearson r > 0.8 | 评分不可靠，回归误判 | 用标注集验证 Judge 一致性 | Ch9 KP 9.3.1 LLM 评委偏差治理 |
| V4 | V | A/B 测试 N ≥ 100 + p < 0.05 + Cohen's d > 0.2 | 小样本误判显著差异 | 检查最近一次 A/B 测试报告 | Ch9 KP 9.5.2 A/B 与灰度 |
| O3 | O | P99 成本异常检测 + 告警 | 成本异常到月底才发现 | 注入异常成本，验证告警在 1h 内触发 | Ch8 KP 8.4.2 成本异常告警 |
| O4 | O | 三层分级存储（热 7d / 温 30d / 冷归档） | 日志存储成本失控 | 检查存储策略配置 | Ch8 §8.6 存储与查询架构 |
| C1 | C | 上下文压缩机制（触发阈值 + 压缩策略） | 长对话 token 溢出 | 模拟 50 轮对话，验证压缩触发 | Ch6 KP 6.2.2 工作记忆压缩 |
| C2 | C | KV-cache 稳定前缀（system+tools 不含动态内容） | 缓存命中率 0，成本翻倍 | 检查 system prompt 是否含时间戳/sessionId | Ch6 KP 6.1.3 KV-cache 前缀复用 |
| T3 | T | 工具数 >25 时启用 Semantic Router Top-K 预选 | 50+ 工具准确率崩塌到 0-20% | 检查路由配置 + 压测准确率 | Ch5 KP 5.4.3 工具怎么选 |
| T4 | T | 写操作幂等键（idempotencyKey + Redis SETNX） | 重复扣款/重复删除 | 模拟重放一次写操作 | Ch5 KP 5.5.1 |
| T5 | T | MCP Server 在沙箱中运行 + SHA-256 pinning | MCP 被劫持注入恶意工具 | 检查 MCP Server 部署位置 | Ch5 KP 5.2.2 |

### P2 优化项（建议通过，不阻断上线）

| # | 层 | 检查项 | 价值 | 章节 |
|---|---|--------|------|------|
| G7 | G | L3 LLM 审查（<500ms，覆盖 5% 复杂攻击） | 拦截正则和分类器漏掉的语义攻击 | Ch10 KP 10.2.2 分级安全管线 |
| E6 | E | Firecracker microVM（125ms 冷启动） | 多租户高安全场景的最强隔离 | Ch4 KP 4.5.1 隔离加固与复用 |
| L6 | L | PipeReAct 混合编排（Pipeline 骨架 + ReAct 肌肉） | 成本与灵活性的最优平衡 | Ch7 KP 7.5.1 PipeReAct |
| V5 | V | 影子流量验证（5% 流量走新版本） | 全量部署前的最后防线 | Ch9 KP 9.5.1 灰度与影子评估 |
| O5 | O | Guides vs Sensors 二分框架 | 前置引导 + 后置感知双保险 | Ch8 KP 8.1.1 可观测性总览 |
| C3 | C | 三层记忆架构（工作/情景/语义） | 跨会话记忆 + 按需检索零浪费 | Ch6 §6.2 工作记忆 |
| C4 | C | 目标锚定 + 漂移检测（cosine < 0.6 告警） | 防止 Agent 偏离原始目标 | Ch6 KP 6.3.1 腐烂与漂移 |

---

## C.4 七层完整度审计表

> 逐层核验每一项的完整度。每项给出：检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节。

### C.4.1 E 层 · 执行环境与沙箱

| # | 检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节 |
|---|---------|--------|-----------|---------|---------|------|
| E-1 | 工具调用在沙箱中执行 | P0 | Agent 破坏宿主机 | `docker inspect` 验证容器参数 | 100% 工具调用在沙箱内 | Ch4 §4.2 |
| E-2 | 非 root 用户运行 | P0 | 容器内提权逃逸 | 检查 Dockerfile USER 指令 | `USER nobody` 或等价 | Ch4 §4.2 |
| E-3 | `--cap-drop=ALL` + seccomp | P0 | 容器突破到宿主机 | `docker inspect` SecurityOpt | cap-drop=ALL + seccomp=json | Ch4 §4.2 |
| E-4 | 资源配额（CPU/内存/磁盘） | P0 | 死循环打满节点 | `docker stats` 验证限制 | CPU 1核/内存 512MB/磁盘 1GB | Ch4 KP 4.4.1 资源配额 |
| E-5 | 全局任务超时 + 单步工具超时 | P0 | 单步卡死拖垮整个任务 | 配置检查 + 压测 | 任务 30s / 工具 10s | Ch4 KP 4.4.4 超时与熔断 |
| E-6 | 文件系统隔离（不共享 /tmp） | P0 | 覆盖生产配置 | 沙箱内写宿主机路径应失败 | 共享目录 = ∅ | Ch4 §4.2 隔离技术选型 |
| E-7 | 快照与回滚（OverlayFS） | P1 | 文件污染后无法恢复 | 触发回滚，验证毫秒级恢复 | 回滚 < 100ms | Ch4 KP 4.4.3 快照与回滚 |
| E-8 | 沙箱预热池 | P1 | 高并发冷启动 2-5s | 压测 P95 启动延迟 | P95 < 100ms | Ch4 KP 4.5.1 预热池与复用 |
| E-9 | 网络白名单代理 | P1 | Agent 访问不该访问的服务 | curl 非白名单域名应失败 | 拦截率 100% | Ch4 KP 4.4.2 网络白名单 |
| E-10 | 三层文件系统（Overlay+Composite+Projection） | P2 | 多用户场景存储成本高 | 检查文件系统架构配置 | 多用户场景启用 | Ch4 §4.6 三层文件系统 |

### C.4.2 T 层 · 工具接口与协议

| # | 检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节 |
|---|---------|--------|-----------|---------|---------|------|
| T-1 | 工具描述五要素 | P0 | 准确率仅 55% | 逐个工具检查 description | 覆盖率 100% | Ch5 KP 5.1.1 四元组与五要素 |
| T-2 | 结构化错误协议 | P0 | 自愈率仅 32% | 触发错误验证 JSON 返回 | `{errorCode,suggestion,retryable}` | Ch5 KP 5.5.3 超时重试与错误 |
| T-3 | 工具数 >25 时 Semantic Router | P1 | 50+ 工具准确率 0-20% | 压测工具选择准确率 | ≥ 92% | Ch5 KP 5.4.3 工具怎么选 |
| T-4 | 写操作幂等键 | P1 | 重复扣款/删除 | 模拟重放写操作 | 重复执行 = 1 次 | Ch5 KP 5.5.1 幂等 |
| T-5 | MCP Server 在沙箱中运行 | P1 | MCP 被劫持 | 检查部署位置 | 100% 在沙箱内 | Ch5 KP 5.2.2 MCP |
| T-6 | MCP 工具描述 SHA-256 pinning | P1 | 工具描述被篡改 | 对比 pin 值 | pin 一致率 100% | Ch5 KP 5.2.2 MCP |
| T-7 | 工具版本管理（新版迁移+旧版 30 天退役） | P2 | 旧版本僵尸调用 | 检查版本管理配置 | 退役窗口 30 天 | Ch5 KP 5.6.2 版本退役 |
| T-8 | 协议选型有书面决策（FC/MCP/A2A） | P2 | 选型不当导致重构 | 检查决策文档 | 有书面记录 | Ch5 §5.3 MCP 和 A2A 怎么配合 |

### C.4.3 C 层 · 上下文与记忆

| # | 检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节 |
|---|---------|--------|-----------|---------|---------|------|
| C-1 | system prompt 不含动态内容 | P1 | KV-cache 命中率 0，成本翻倍 | grep system prompt 中的时间戳/sessionId | 动态内容 = 0 | Ch6 KP 6.1.3 KV-cache 前缀复用 |
| C-2 | 上下文压缩机制 | P1 | 50 轮后 token 溢出 | 模拟长对话验证压缩触发 | 触发阈值 + 压缩策略 | Ch6 KP 6.2.2 工作记忆压缩 |
| C-3 | KV-cache 命中率监控 | P1 | 成本失控无感知 | 查看监控指标 | 命中率 ≥ 50% | Ch6 KP 6.1.4 缓存击穿诊断 |
| C-4 | 三层记忆架构 | P2 | 跨会话失忆 | 检查记忆层配置 | 工作+情景+语义 | Ch6 §6.2 工作记忆 |
| C-5 | 关键约束每 10 轮重复注入 | P2 | 上下文腐烂，早期信息丢失 | 检查注入逻辑 | 间隔 ≤ 10 轮 | Ch6 KP 6.3.1 腐烂与漂移 |
| C-6 | 上下文腐烂诊断 probe | P2 | 腐烂无感知无法触发压缩 | 每 N 步验证早期信息召回 | 召回率 ≥ 80% | Ch6 KP 6.3.1 腐烂与漂移 |
| C-7 | 目标锚定 + 漂移检测 | P2 | Agent 偏离原始目标 | embedding cosine similarity 监控 | cosine < 0.6 告警 | Ch6 KP 6.3.1 腐烂与漂移 |

### C.4.4 L 层 · 生命周期与编排

| # | 检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节 |
|---|---------|--------|-----------|---------|---------|------|
| L-1 | 步数硬上限 | P0 | $47K 循环事故重演 | 配置检查 + 压测 | ≤ 30 步 | Ch7 KP 7.1.2 循环收敛性保证 |
| L-2 | 连续重复检测（N=3 跳出） | P0 | 在空结果上死循环 | 模拟连续重复 | 3 次后强制跳出 | Ch7 KP 7.1.2 循环收敛性保证 |
| L-3 | per-session 成本上限 + 熔断 | P0 | 单用户拖垮组织预算 | 模拟超限 | 自动终止 | Ch17 KP 17.3.1 预算熔断 |
| L-4 | 多层停止机制（L+V+O 三层） | P1 | 单层失效无兜底 | 检查三层停止配置 | 三层独立 | Ch7 KP 7.1.2 循环收敛性保证 |
| L-5 | 检查点/恢复机制 | P1 | 崩溃后从头开始 | 杀进程验证恢复 | 可从 checkpoint 续跑 | Ch7 KP 7.7.2 检查点设计 |
| L-6 | 编排模式选型有书面记录 | P1 | 默认 ReAct 成本失控 | 检查决策文档 | 有选型记录 | Ch7 KP 7.4.1 横向基准与决策树 |
| L-7 | 多 Agent 独立沙箱 + 独立步数 | P1 | 子 Agent 失控拖垮父 Agent | 检查多 Agent 配置 | 独立隔离 | Ch15 KP 15.6.1 多 Agent 资源隔离 |
| L-8 | Agent 拆分遵循拆分判据矩阵 | P2 | 过度拆分 O(N²) 通信开销 | 检查拆分文档 | 符合判据 | Ch15 KP 15.1.1 单 Agent 还是多 Agent |

### C.4.5 O 层 · 可观测性

| # | 检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节 |
|---|---------|--------|-----------|---------|---------|------|
| O-1 | 五标签成本归因 | P0 | 月底账单看不清 | 查询近 24h 成本按标签聚合 | 五标签全覆盖 | Ch8 KP 8.4.1 Token 成本追踪 |
| O-2 | JSONL 事件日志 + traceId | P0 | 出事无法回放推理过程 | 抽查一条 trace 完整还原 | 100% 调用有 traceId | Ch8 KP 8.5.1 日志与 trace 关联 |
| O-3 | P99 成本异常检测 + 告警 | P1 | 成本异常月底才发现 | 注入异常验证告警 | 1h 内触发 | Ch8 KP 8.4.2 成本异常告警 |
| O-4 | 三层分级存储（热/温/冷） | P1 | 日志存储成本失控 | 检查存储策略 | 热 7d/温 30d/冷归档 | Ch8 §8.6 存储与查询架构 |
| O-5 | P0 观测点 100% 采集 | P1 | 关键推理轨迹丢失 | 检查采集配置 | 模型/工具/入站/离站 100% | Ch8 §8.3 观测点设计 |
| O-6 | 每个 Middleware 的假设声明 + drift 检测 | P2 | 假设失效无感知 | 检查 Middleware 假设文档 | 覆盖率 ≥ 80% | Ch8 KP 8.1.2 中间件假设与漂移 |
| O-7 | Guides vs Sensors 二分框架 | P2 | 只有事后感知无前置引导 | 检查 Guides 配置 | 前置+后置双保险 | Ch8 KP 8.1.1 可观测性总览 |

### C.4.6 V 层 · 验证与评估

| # | 检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节 |
|---|---------|--------|-----------|---------|---------|------|
| V-1 | 100+ 任务回归评估集 | P0 | 改一处坏三处 | 检查评估集规模 | ≥ 100 任务 | Ch9 KP 9.1.2 评估体系三层 |
| V-2 | CI 门禁：故障率 > 10% 阻断 | P0 | 带病上线 | 触发回归验证门禁 | 阻断生效 | Ch16 KP 16.1.1 上线门禁 |
| V-3 | 评估集按难度切分（简:中:难=3:5:2） | P1 | 评估偏差，难度分布失衡 | 检查评估集分布 | 3:5:2 ± 10% | Ch9 KP 9.4.2 评估集难度切分 |
| V-4 | LLM-as-Judge 三招偏差治理 + r > 0.8 | P1 | 评分不可靠 | 用标注集验证一致性 | Pearson r > 0.8 | Ch9 KP 9.3.1 LLM 评委偏差治理 |
| V-5 | A/B 测试 N ≥ 100 + p < 0.05 + d > 0.2 | P1 | 小样本误判 | 检查最近 A/B 报告 | 三条件全满足 | Ch9 KP 9.5.2 A/B 与灰度 |
| V-6 | 影子流量验证（5% → 100%） | P2 | 全量部署风险高 | 检查灰度配置 | 渐进式扩流 | Ch9 KP 9.5.1 灰度与影子评估 |
| V-7 | 自有评估集不与公开基准共享数据 | P2 | 基准污染，分数虚高 | 对比评估集与 SWE-bench | 数据无重叠 | Ch9 KP 9.3.2 基准污染防范 |

### C.4.7 G 层 · 治理与安全

| # | 检查内容 | 优先级 | 不做的代价 | 验证方法 | 量化阈值 | 章节 |
|---|---------|--------|-----------|---------|---------|------|
| G-1 | 四检查点全覆盖（输入/工具/输出/会话） | P0 | 攻击从任意位置进入 | 检查 Middleware 链配置 | 4/4 检查点 | Ch10 KP 10.2.1 检查点威胁与防护 |
| G-2 | L1 正则过滤（OWASP Top 10） | P0 | 提示注入 | AgentDojo 100 条测试 | 拦截率 ≥ 95% | Ch10 KP 10.2.2 分级安全管线 |
| G-3 | 危险操作白名单 + 人工审批 | P0 | `DROP TABLE`/`rm -rf` | grep 危险 @Tool | requiresApproval | Ch10 KP 10.2.1 检查点威胁与防护 |
| G-4 | PII 脱敏（正则 + 掩码） | P0 | 输出敏感信息 | 50 条 PII 测试 | 泄露率 = 0 | Ch10 KP 10.2.1 检查点威胁与防护 |
| G-5 | L2 分类器检测（<50ms） | P1 | 漏掉变体注入 | AgentDojo 高级用例 | 拦截率 ≥ 85% | Ch10 KP 10.2.2 分级安全管线 |
| G-6 | YAML 声明式策略 + Git 版控 | P1 | 策略散落不可审计 | 检查策略仓库 | Git 版控 | Ch10 KP 10.3.1 策略即代码 |
| G-7 | WORM 审计日志 + 决策链追溯 | P1 | 事故后无法归责 | 抽查决策追溯 | 6 层归因完整 | Ch10 KP 10.3.2 审计日志 |
| G-8 | 治理有效性指标（P/R/FPR） | P2 | 误杀正常请求或漏放攻击 | 查看治理指标看板 | P>85%/R>90%/FPR<5% | Ch10 KP 10.6.2 治理规则有效性 |
| G-9 | 新规则影子模式运行 1-2 周 | P2 | 规则误杀线上流量 | 检查规则上线流程 | 影子 1-2 周 | Ch10 KP 10.6.2 治理规则有效性 |

### 审计结果判定

| P0 通过率 | P1 通过率 | 判定 | 行动 |
|-----------|-----------|------|------|
| 100% | ≥ 80% | ✅ **可上线** | 进入 C.3 完整门禁复核 |
| 100% | 60-80% | 🟡 **有条件上线** | P1 缺失项 7 天内补齐 + 严密监控 |
| 100% | < 60% | 🟠 **暂缓上线** | 补齐 P1 至 80% 后重新审计 |
| < 100% | — | 🔴 **不可上线** | 补齐所有 P0 后重新审计 |

---

## C.5 层间耦合回归矩阵

> ETCLOVG 七层不是正交的。改了一层，必须回归验证相邻层。本表给出"改 X 层 → 必须重新验证 Y 层"的完整映射，避免局部修改引入跨层缺陷。

### 改动影响矩阵

| 改了什么 | 必须回归验证 | 验证内容 | 理由 |
|---------|------------|---------|------|
| **E 层** 沙箱配置 | T 层 | 工具调用是否仍能在沙箱内正常执行 | 沙箱策略变化可能阻断工具的网络/文件访问 |
| **E 层** 沙箱配置 | O 层 | 事件日志是否仍能正常采集 | 沙箱可能隔离了日志收集器 |
| **E 层** 资源配额 | L 层 | 步数超时是否与新配额匹配 | CPU/内存降低可能导致工具超时，触发 L 层熔断 |
| **T 层** 工具描述 | C 层 | KV-cache 命中率是否下降 | 工具定义变化破坏稳定前缀，缓存失效 |
| **T 层** 工具数量 | L 层 | 步数上限是否需要调整 | 工具增多可能需要更多探索步数 |
| **T 层** 工具协议 | G 层 | 工具白名单是否覆盖新协议 | MCP/A2A 引入新的工具发现路径，需更新白名单 |
| **C 层** 压缩策略 | V 层 | 压缩后评估集分数是否下降 | 压缩可能丢失关键信息，导致回归故障 |
| **C 层** 记忆架构 | O 层 | 成本归因是否覆盖新记忆层 | 新增语义记忆层需要新的成本标签 |
| **C 层** KV-cache 前缀 | T 层 | 工具定义变化是否破坏缓存 | 前缀中包含工具定义，工具变则缓存失效 |
| **L 层** 步数上限 | V 层 | 评估集是否有任务因步数减少而失败 | 步数收紧可能导致复杂任务半途而废 |
| **L 层** 编排模式 | O 层 | 事件日志格式是否兼容新模式 | PipeReAct vs 纯 ReAct 的事件结构不同 |
| **L 层** 熔断阈值 | G 层 | 熔断是否误触发安全检查 | 成本熔断可能中断 G 层的必要安全审查 |
| **O 层** 观测点 | V 层 | 评估依赖的指标是否仍可采集 | 评估集可能依赖被移除的观测点 |
| **O 层** 成本归因 | L 层 | per-session 预算是否仍能准确计算 | 归因标签变化影响预算统计 |
| **V 层** 评估集 | G 层 | 新增评估用例是否触犯安全策略 | 评估集可能包含敏感测试输入 |
| **V 层** Judge 配置 | O 层 | Judge 调用成本是否被归因 | LLM-as-Judge 本身消耗 token，需计入成本 |
| **G 层** 安全策略 | T 层 | 工具白名单变化是否阻断正常工具 | 新策略可能误杀合法工具调用 |
| **G 层** 安全策略 | L 层 | 安全审查延迟是否影响步数超时 | L3 LLM 审查 500ms 可能触发单步超时 |
| **G 层** PII 脱敏 | V 层 | 脱敏是否破坏评估集的标注 | 评估输出被脱敏后可能无法与标注对比 |

### 回归测试最小集

每次跨层变更后，至少跑以下回归：

| 变更类型 | 最小回归集 | 预估耗时 |
|---------|-----------|---------|
| E 层变更 | 10 个工具调用任务 + 沙箱逃逸测试 | 30 分钟 |
| T 层变更 | 工具选择准确率测试（BFCL 子集）+ KV-cache 命中率 | 1 小时 |
| C 层变更 | 50 轮长对话测试 + 评估集全量回归 | 2 小时 |
| L 层变更 | 评估集全量回归 + 步数分布统计 | 2 小时 |
| O 层变更 | 成本归因完整性测试 + 一条 trace 完整回放 | 30 分钟 |
| V 层变更 | Judge 一致性验证（Pearson r）+ 评估集全量 | 3 小时 |
| G 层变更 | AgentDojo 100 条注入测试 + 50 条 PII 测试 | 1 小时 |

---

## C.6 运维就绪检查

> 上线不是终点，是运维的起点。本节检查 Agent 上线后的运维体系是否就绪——SLO、Runbook、On-call 三件套。

### C.6.1 SLO 定义

| SLI 指标 | SLO 目标 | 告警阈值 | 测量窗口 | 章节 |
|---------|---------|---------|---------|------|
| 端到端成功率 | ≥ 85% | < 80% → P1 告警 | 滚动 1h | Ch17 §17.1 |
| P95 端到端延迟 | < 30s | > 60s → P1 告警 | 滚动 5min | Ch17 §17.1 |
| 单任务成本 P95 | < $2 | > $5 → P2 告警 | 滚动 24h | Ch17 §17.3 |
| 工具调用错误率 | < 5% | > 10% → P1 告警 | 滚动 5min | Ch8 §8.4 |
| 循环卡死发生率 | < 0.5% | > 1% → P0 告警 | 滚动 1h | Ch7 §7.1 |
| 安全拦截误杀率 | < 5% | > 10% → P2 告警 | 滚动 24h | Ch10 KP 10.6.2 治理规则有效性 |

### C.6.2 Runbook 必备项

| 事故类型 | 触发条件 | 应急动作 | 恢复条件 | 章节 |
|---------|---------|---------|---------|------|
| 成本失控 | burnRate > 1.5 | ① 自动熔断 ② On-call 介入 ③ 检查燃烧率看板 | burnRate < 1.0 持续 1h | Ch17 §17.2 |
| 循环卡死 | 步数上限触发率 > 1% | ① 检查最近变更 ② 临时降低步数上限 ③ 排查工具返回 | 卡死率 < 0.5% | Ch7 §7.1 |
| 安全事件 | AgentDojo 类注入成功 | ① 立即下线 ② 审计日志追溯 ③ 补 G 层规则 | 规则上线 + 影子模式验证通过 | Ch10 KP 10.3.2 审计日志 |
| 模型漂移 | K-S test p < 0.01 | ① 切换备用模型 ② 排查模型更新 ③ 评估集验证 | 新模型评估通过 | Ch12 §12.5 |
| 回归故障 | CI 门禁阻断 | ① 阻止部署 ② 定位故障用例 ③ 修复后重跑 | 评估集故障率 < 10% | Ch16 §16.1 |

### C.6.3 On-call 就绪

- [ ] Agent 监控看板已接入值班手机/Slack/钉钉
- [ ] P0 告警 5 分钟内有人响应（24×7 或按业务时段）
- [ ] Runbook 文档可访问，每条 Runbook 经过至少一次演练
- [ ] 熔断/降级开关有手动触发入口（不完全依赖自动）
- [ ] 回滚流程文档化，可在 10 分钟内完成回滚
- [ ] 事故复盘模板就绪（五步：时间线 → 根因 → 缺陷登记 → 回归覆盖 → 加固部署）

---

## C.7 ETCLOVG 七层速查表

### E 层 · 执行环境与沙箱
- **核心概念**：隔离、快照、回滚、资源限额
- **设计决策**：进程级 vs Docker vs gVisor vs Firecracker——隔离强度 × 性能开销的权衡
- **AgentScope 框架提供**：无沙箱抽象（E 层能力全部由工程侧自建）
- **CodePilot 教学实现**（配套仓库教学实现，非框架内置）：`SandboxPool`（预热池复用）、`DockerSandboxClient`/`E2BSandboxClient`/`DaytonaSandboxClient`/`KubernetesSandboxClient`（四种隔离方案）、`ResourceLimits`（八维硬限制）、`NetworkPolicy`（网络白名单）、`FileSystemPolicy`、`OverlayFSSnapshotManager`（快照回滚与三层文件系统）、`TimeoutMiddleware`
- **反模式**：生产环境中不启用沙箱、沙箱与宿主机共享文件系统

### T 层 · 工具接口与协议
- **核心概念**：工具四元组 = 描述（Description）+ 参数（Parameters）+ 执行逻辑（Logic）+ 错误协议（Error Protocol）；其中"描述"组件的内部结构是五要素（做什么/什么时候用/参数说明/返回值说明/注意事项）
- **设计决策**：粒度（黄金粒度原则）、协议选择（Function Calling / MCP / A2A）、语义路由（Top-K 预选）
- **AgentScope 框架提供**：`@Tool`、`@ToolParam`、`Toolkit`、`McpClientWrapper`（`io.agentscope.core.tool.mcp`，MCP 集成）
- **CodePilot 教学实现**（配套仓库教学实现，非框架内置）：`ToolCallingMiddleware`、`SemanticToolRouter`（语义路由）、`ToolPolicyAdvisor`（白名单/频率/配额）、`ToolValidationAdvisor`、`DynamicToolRegistry`（动态发现）、`ParallelToolExecutor`、`SagaCoordinator`（可回滚）、`ToolVersionManager`
- **反模式**：工具描述过于模糊、工具数量超过 50 不启用语义路由、错误直接抛异常不返回结构化错误

### C 层 · 上下文与记忆
- **核心概念**：三层记忆（工作 / 情景 / 语义）、位置策略（首尾优先）、压缩（提取式 / 生成式 / 混合式）
- **设计决策**：KV-cache 前缀稳定化、压缩触发时机、时间衰减权重
- **AgentScope 框架提供**：`Memory`、`InMemoryChatMemory`（会话记忆抽象）
- **CodePilot 教学实现**（配套仓库教学实现，非框架内置）：`FiveZoneBudgetAllocator`（五区预算）、`WorkingMemoryManager`（滑动窗口压缩）、`ContextCompactor`、`CacheAwareSystemPromptBuilder`/`CacheHitRateDiagnoser`（KV-cache）、`EpisodicMemoryMiddleware`、`SemanticMemoryDistiller`、`DriftDetector`（腐烂/漂移）、`MessageChatMemoryMiddleware`、`VectorStoreChatMemoryMiddleware`
- **反模式**：系统提示中含动态内容导致缓存命中率为 0、所有信息全量注入不压缩

### L 层 · 生命周期与编排
- **核心概念**：工程化 ReAct（五组件）、步数限制、三种停止机制、PipeReAct 混合编排
- **设计决策**：编排模式选择（Pipeline / 黑板 / 层级 / 网状）、检查点粒度、Agent 拆分判据
- **AgentScope 框架提供**：`ReActAgent`（`io.agentscope.core.ReActAgent`，核心 Agent）
- **CodePilot 教学实现**（配套仓库教学实现，非框架内置）：`ReActOrchestrator`（步数/重复/超时/熔断四道防护）、`PipelineOrchestrator`、`Blackboard`、`SupervisorAgent`（层级）、`MeshOrchestrator`（网状）、`PipeReActOrchestrator`（混合编排）、`PlanExecuteVerifyController`、`StateMachineManager`、`CheckpointManager`（断点续传）
- **反模式**：无步数上限导致 $47K 循环事故、过度拆分 Agent 导致 O(N²) 通信开销

### O 层 · 可观测性
- **核心概念**："Harness 即假设"、三件套（Traces / Metrics / Logs）、成本归因五标签
- **设计决策**：P0/P1/P2 观测点分级、热温冷三层存储、观测旁路化（不影响业务）
- **AgentScope 框架提供**：无专用追踪门面；链路埋点走 OpenTelemetry 标准入口 `GlobalOpenTelemetry.getTracer()`
- **CodePilot 教学实现**（配套仓库教学实现，非框架内置）：`TracerMiddleware`/`TracePropagator`（Agent span）、`ObservabilityMiddleware`（旁路异步发送）、`EventLogRecorder`（JSONL 事件日志）、`CostTracker`/`CostAttributionMiddleware`（五标签归因）、`BurnRateCalculator`（燃烧率熔断）、`AssumptionDriftDetector`（假设漂移）、`ReplayEngine`（时间旅行回放）
- **反模式**：观测事后加导致关键推理轨迹丢失、成本异常到月底才发现

### V 层 · 验证与评估
- **核心概念**：四阶段质量控制循环、LLM-as-Judge（三招治偏差）、自有评估集 + 影子流量
- **设计决策**：锚定标准、嵌入式评判 vs 终点评判、A/B 样本量（N ≥ 100 + Cohen's d > 0.2）
- **AgentScope 框架提供**：无（V 层组件全部由工程侧自建）
- **CodePilot 教学实现**（配套仓库教学实现，非框架内置）：`EvaluationAdvisor`（评估状态机）、`ReadinessCheckAdvisor` + `SolvabilityEstimator`（就绪检查/可解性预估）、`EmbeddedValidationAdvisor`（嵌入式评判）、`RegressionRunner`（回归门禁）、`LLMJudgeAdvisor`（LLM-as-Judge）、`JudgeDriftDetector`、`ShadowTrafficRouter`（影子流量）、`ABTestRunner`、`RegressionEvaluator`（ch11 全景组装）
- **反模式**：仅靠标准基准（SWE-bench Verified 被污染 ~49pp）、小样本 A/B 误判显著差异

### G 层 · 治理与安全
- **核心概念**：三层治理塔（预防+检测+归责）、四检查点、安全管线分级（L1/L2/L3）
- **设计决策**：YAML 声明式策略、风险自适应（1-125 分 → 三档模式）、"信任但验证"哲学
- **AgentScope 框架提供**：无专用权限引擎（治理逻辑通过中间件链实现）
- **CodePilot 教学实现**（配套仓库教学实现，非框架内置）：`SafeGuardMiddleware`（ch02 基础护栏）、`InputGuardAdvisor`/`ToolGuardAdvisor`/`OutputGuardAdvisor`（四检查点，ch10 另有同名 Middleware 版本）、`ConstitutionValidator` + `constitution.yml`（声明式宪法）、`AuditLogAdvisor`（WORM 审计）、`RiskAdaptiveGovernor`（风险自适应）、`SupplyChainGuard`（供应链）、`SecurityCheckpoint`
- **反模式**：治理 = 锁死 Agent、无决策归因链导致事故后无法追责


## C.8 AgentScope API 速查

> **API 正确性声明**：以下代码展示的是 AgentScope 2.x 的**真实 API**。书中其他章节的代码示例为了可读性做了适度简化（例如将完整的 `Agent`/`RuntimeContext`/`Function` 四参数签名简化为单参数形式）。实际开发时请以本节 API 为准，或直接参考 AgentScope 官方示例代码（`agentscope-examples/` 目录）。

```java
// ========== ReActAgent：Agent 入口 ==========
Toolkit toolkit = new Toolkit();
toolkit.registerObject(new MyTools());  // 注册 @Tool 方法集合

ReActAgent agent = ReActAgent.builder(model)
    .name("Assistant")
    .sysPrompt("You are a helpful assistant.")
    .toolkit(toolkit)
    .middlewares(List.of(
        new SafeGuardMiddleware(refusalGuard),    // CodePilot 教学实现（非框架内置）
        new MessageChatMemoryMiddleware(memory),  // CodePilot 教学实现
        new SimpleLoggerMiddleware(),             // CodePilot 教学实现
        new TracerMiddleware()                    // CodePilot 教学实现（OTel 埋点走 GlobalOpenTelemetry.getTracer()）
    ))
    .maxIters(10)
    .build();
Msg response = agent.call("Hello").block();

// ========== @Tool：工具声明 ==========
@Tool(description = "查询指定城市的天气。仅支持未来7天。")
public WeatherResponse getWeather(
    @ToolParam(description = "城市名称，如'北京'") String city,
    @ToolParam(description = "日期，格式YYYY-MM-DD") String date
) { ... }

// ========== Middleware 链 ==========
// MiddlewareBase：AgentScope 2.x 标准中间件接口，提供 5 个拦截钩子
// onAgent / onReasoning / onActing / onModelCall / onSystemPrompt
public interface MiddlewareBase {
    default Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx,
            AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }
    // ... 其他钩子同理
}

// ========== VectorStore：向量检索 ==========
List<Document> docs = vectorStore.similaritySearch(
    SearchRequest.query("search query").withTopK(5)
);

// ========== 链路追踪：OpenTelemetry 标准入口 ==========
// AgentScope 不提供专用追踪门面，埋点统一走 OTel 标准 API
// （CodePilot 的 TracerMiddleware 是配套仓库教学实现，非框架内置）
Tracer tracer = GlobalOpenTelemetry.getTracer("io.etclovg.codepilot.observability");
Span span = tracer.spanBuilder("agent.call").startSpan();
```


## C.9 CodePilot 完整代码仓库索引

> CodePilot 配套仓库：`codepilot/` 目录，21 个 Maven 模块（19 个章节模块 + `codepilot-core` + `codepilot-starter`），451 个 main Java 文件、354 个测试方法，可通过 `mvn clean install -DskipTests` 编译，`codepilot-starter` 模块统一启动。模块内类名与书稿代码示例一致；自研类均在 `io.etclovg.codepilot` 包下并标注"配套仓库教学实现，非框架内置"。完整清单见 [codepilot/README.md](../codepilot/README.md)。

| 章节 | 模块名 | 核心类 | 代码状态 | 测试数 |
|------|--------|--------|---------|--------|
| Ch1 | `ch01-foundation` | `QuickstartAgent`、`PdaLoopService`（PDA 闭环）、`HarnessGuard`、`FaultDetectionMiddleware`、`StepLimitMiddleware`、`UsageLimitMiddleware` | ✅ Tested | 38 |
| Ch2 | `ch02-definition` | `GoalAnchorMiddleware`（目标锚定）、`SafeGuardMiddleware`、`RefusalGuard`、`LoopDetectionMiddleware`、`ToolCallingMiddleware`、`MessageChatMemoryMiddleware`、`SimpleLoggerMiddleware` | 🟡 Runnable | 0 |
| Ch3 | `ch03-behavior` | `AgentStateManager`（状态外置）、`FaultDetector`、`VerificationMiddleware`、`TokenCostTracker`、`EventBus`、`RetryOrchestrationMiddleware`、`CodePilotSkeleton` | 🟡 Runnable | 0 |
| Ch4 | `ch04-sandbox` | `SandboxPool`（预热池）、`DockerSandboxClient`/`E2BSandboxClient`/`DaytonaSandboxClient`/`KubernetesSandboxClient`、`ResourceLimits`、`NetworkPolicy`、`FileSystemPolicy`、`OverlayFSSnapshotManager`、`TimeoutMiddleware` | ✅ Tested | 18 |
| Ch5 | `ch05-tools` | `SemanticToolRouter`、`ToolPolicyAdvisor`、`ToolValidationAdvisor`、`DynamicToolRegistry`、`ParallelToolExecutor`、`SagaCoordinator`、`ToolVersionManager`、`McpEchoServer` | ✅ Tested | 44 |
| Ch6 | `ch06-memory` | `FiveZoneBudgetAllocator`（五区预算）、`WorkingMemoryManager`、`ContextCompactor`、`CacheAwareSystemPromptBuilder`/`CacheHitRateDiagnoser`、`EpisodicMemoryMiddleware`、`SemanticMemoryDistiller`、`DriftDetector`、`VectorStoreChatMemoryMiddleware` | ✅ Tested | 13 |
| Ch7 | `ch07-orchestration` | `ReActOrchestrator`（四道防护）、`PipelineOrchestrator`、`Blackboard`、`SupervisorAgent`、`MeshOrchestrator`、`PipeReActOrchestrator`、`PlanExecuteVerifyController`、`StateMachineManager`、`CheckpointManager` | ✅ Tested | 36 |
| Ch8 | `ch08-observability` | `TracerMiddleware`/`TracePropagator`、`ObservabilityMiddleware`、`EventLogRecorder`、`CostTracker`/`CostAttributionMiddleware`、`BurnRateCalculator`、`AssumptionDriftDetector`、`ReplayEngine` | ✅ Tested | 19 |
| Ch9 | `ch09-evaluation` | `EvaluationAdvisor`（评估状态机）、`ReadinessCheckAdvisor`/`SolvabilityEstimator`、`EmbeddedValidationAdvisor`、`RegressionRunner`、`LLMJudgeAdvisor`、`JudgeDriftDetector`、`ShadowTrafficRouter`、`ABTestRunner` | ✅ Tested | 33 |
| Ch10 | `ch10-governance` | `InputGuardAdvisor`/`ToolGuardAdvisor`/`OutputGuardAdvisor`（四检查点）、`ConstitutionValidator` + `constitution.yml`、`AuditLogAdvisor`、`RiskAdaptiveGovernor`、`SupplyChainGuard`、`SecurityCheckpoint`、`SessionMonitor` | ✅ Tested | 84 |
| Ch11 | `ch11-etcclovg` | `HarnessAssemblyConfig`（七层 Middleware 一次性注册）、`FullHarnessConfig`、`RegressionEvaluator` | ✅ Tested | 4 |
| Ch12 | `ch12-model` | `LayeredModelRouter`/`ModelRouter`（三层路由）、`ModelFallbackService`/`CascadeMiddleware`、`ModelSelectionTriangle`、`TokenOptimizer`、`PrefixCacheMonitorMiddleware`、`ModelEnsemble` | 🟡 Runnable | 0 |
| Ch13 | `ch13-data` | `ThreeLayerKnowledgeRouter`、`RagRetriever`、`DataCleaner`、`AstSafetyScanner`（生成代码安全扫描）、`ApprovalGateway`（工具制造审批门）、`BiasGuard` | ✅ Tested | 17 |
| Ch14 | `ch14-planning` | `ReasoningRouter`（ReAct/Plan-Execute/ToT/ReWOO 四范式）、`ReActMiddleware`、`PlanExecuteMiddleware`、`ToTMiddleware`、`ReWOOMiddleware`、`DynamicUpgrader`、`ThreeTierFallback`、`HumanHandoffMiddleware`、`PlanPreEvaluator` | 🟡 Runnable | 0 |
| Ch15 | `ch15-multiagent` | `DagDecomposer`、`TopologyRouter`（四种拓扑）、`WorkerCircuitBreaker`、`ConsistencyVerifier`/`SchemaValidator`/`LlmJudge`、`MastFailureDetector`（14 种失败模式）、`ToolPermissionGate`、`GovernedMemoryStore`、`SessionManager`、`OtelAgentConfig` | 🟡 Runnable | 0 |
| Ch16 | `ch16-mlops` | `GatePipelineOrchestrator`（四阶段门禁）、`AgentBundleBuilder`/`AgentBundleRegistry`、`EvalWorkerPool`/`RateLimitAwareScheduler`、`CanaryController`/`ShadowTrafficRouter`、`CompensationExecutor`、`ModelUpdateManager`、`IncidentPostmortemTemplate`、`RoadmapAssessor` | ✅ Tested | 11 |
| Ch17 | `ch17-reliability` | `FourLayerBudgetEnforcer`（四层硬预算）、`ThreeTierControlLoop`、`ThreeTierSLOCalculator`、`BurnRateMonitor`、`CostHeatmapGenerator`、`TieredLogManager`、`ManagerQuadrantCalculator`、`HotStorageClient`/`WarmStorageClient`/`ColdStorageClient` | ✅ Tested | 37 |
| Ch18 | `ch18-case-study` | `CodePilotFullHarness`（终版全景）、`SandboxMiddleware`、`ToolValidationMiddleware`、`EvaluationMiddleware` | 🟡 Runnable | 0 |
| Ch19 | `ch19-future` | `HarnessComplexityAnalyzer`、`MetaHarnessGovernance`、`ShadowModeEvaluator`/`SelfImprovementProposal`、`HarnessInvestmentShift`、`ConfigurationBundleManager` | 🟡 Runnable | 0 |
| Full | `codepilot-starter` | `CodepilotApplication`（Spring Boot 完整装配） | ✅ Runnable | — |
| Core | `codepilot-core` | `Layer`（七层枚举）、`AbstractLayerMiddleware`（所有自研中间件基类，配套仓库教学实现） | ✅ Runnable | — |

**代码状态图例**：✅ 可运行 + 有单元测试 | 🟡 可运行（测试覆盖不全或暂无测试）


## C.10 ETCLOVG 框架映射指南：AgentScope ↔ LangChain/Python

> 本书以 Java + AgentScope 为实战基线，但 ETCLOVG 七层是框架无关的抽象蓝图。本节提供每一层在 AgentScope、LangChain (Python)、CrewAI 中的概念映射和关键 API 对照，帮助非 Java 读者将本书的设计决策翻译为自己所用框架的实现。
>
> **声明**：本书以 Java + AgentScope 为实战基线，并结合 AgentScope（阿里云开源，Apache 2.0 许可）的 harness 模块作为生产级参考实现。本书提及的所有产品、框架、API 名称——包括但不限于 AgentScope / Spring Boot（VMware/Pivotal）、LangChain / LangGraph（LangChain Inc.）、CrewAI（CrewAI Inc.）、Anthropic Claude、OpenAI GPT、Google Gemini、Docker、Kubernetes、MCP（Model Context Protocol，Anthropic）、A2A（Agent-to-Agent，Google）等——均为其各自所有者的商标或产品名称。本书对上述项目的引用（包括 API 名称、源码路径、架构概念、文档表述）均基于公开文档和作者的技术分析，用于教学与工程参考目的，不代表各项目官方的定位或背书，亦不存在从属或商业合作关系。

### E 层 · 执行环境与沙箱

| 概念 | AgentScope (Java) | LangChain (Python) | CrewAI (Python) |
|------|-------------------|-------------------|-----------------|
| 沙箱隔离 | CodePilot 教学实现：`SandboxPool` + `DockerSandboxClient`（Docker SDK） | `langchain.tools` + 自定义 subprocess | 框架不提供，需自建 |
| 超时控制 | CodePilot 教学实现：`TimeoutMiddleware` | `tool.run(timeout=...)` | `Task(timeout=...)` |
| 资源限制 | CodePilot 教学实现：`ResourceLimits`（八维硬限制） | 通过 Docker SDK 设置 | 不支持 |
| 快照/回滚 | CodePilot 教学实现：`OverlayFSSnapshotManager`（OverlayFS） | `langgraph.checkpoint` | 不支持 |

**翻译要点**：CrewAI 的 E 层基本为空白——如果用它，沙箱隔离需要额外搭建。LangChain 的 `langgraph.checkpoint` 提供了类似本书 Ch7 检查点的功能，但作用域仅限于编排状态。

### T 层 · 工具接口

| 概念 | AgentScope | LangChain | CrewAI |
|------|-----------|-----------|--------|
| 工具声明 | `@Tool(description="...")` + `Toolkit.registerObject()` | `@tool` 装饰器 或 `StructuredTool.from_function()` | 传入函数列表给 `Agent(tools=[...])` |
| 参数校验 | `@ToolParam(description="...")` + Spring Validation | Pydantic `BaseModel` + `Field(description=...)` | 无内置校验 |
| 工具描述工程 | 五要素体现在 `description` + 参数文档 | 同——`description` + Pydantic docstring | `description` 字符串 |
| MCP 集成 | `McpClientWrapper`（`io.agentscope.core.tool.mcp`） | `langchain-mcp-adapters` | 不支持 |
| 工具错误协议 | 自定义 `ToolException` → 结构化错误返回 | `ToolException` → 自动反馈 | 无标准协议 |

**翻译要点**：五要素工具描述原则是框架无关的——你在 AgentScope 的 `description` 中写的"名称/参数/返回值/错误模式/使用示例"，在 LangChain 的 `@tool` 装饰器中同样适用。CrewAI 缺少参数校验和错误协议——如果用它，这两块需要自行补上。

### C 层 · 上下文与记忆

| 概念 | AgentScope | LangChain | CrewAI |
|------|-----------|-----------|--------|
| 工作记忆 | 需自定义（CodePilot 示例：`MessageChatMemoryMiddleware`） | `Checkpointer`（线程级持久化） | 框架内置 shared memory |
| 情景记忆 | 需自定义（CodePilot 示例：`VectorStoreChatMemoryMiddleware`） | `Store`（跨会话记忆） | 不支持 |
| 语义记忆 | CodePilot 教学实现：`SemanticMemoryDistiller`、`InternalRAGService` | LangChain 内置 `VectorStore` + `Document` | 不支持 |
| 上下文压缩 | CodePilot 教学实现：`ContextCompactor`、`WorkingMemoryManager` | `summarizationMiddleware` | 不支持 |
| KV-cache | CodePilot 教学实现：`CacheAwareSystemPromptBuilder`、`CacheHitRateDiagnoser` | Anthropic API 原生支持 `cache_control` | 不支持 |

**翻译要点**：LangChain 的记忆体系与 AgentScope 构成镜像——`Checkpointer`≈工作记忆、`Store`≈情景记忆。CrewAI 只有 shared memory 没有分层，三层记忆架构需要自行实现。

### L 层 · 生命周期与编排

| 概念 | AgentScope | LangChain | CrewAI |
|------|-----------|-----------|--------|
| ReAct 循环 | `ReActAgent` 内置（AgentScope core 真实提供） | `create_react_agent()` | Agent 默认行为模式 |
| Pipeline 编排 | 需自定义（CodePilot 示例：`PipelineOrchestrator`） | `RunnableSequence` / `LCEL` (`|`) | 通过 `Task` 链实现 |
| PipeReAct 混合 | 需自定义（CodePilot 示例：`PipeReActOrchestrator`） | LangGraph `StateGraph` + 条件边 | 不支持混合模式 |
| 步数限制 | 需自定义（CodePilot 示例：`StepLimitMiddleware`） | `agent_executor.max_iterations` | `Task(max_iter=...)` |
| 熔断降级 | CodePilot 教学实现：`ReActOrchestrator`（步数/超时/熔断四道防护）、多 Agent 场景 `WorkerCircuitBreaker` | 需自定义 Callback | 不支持 |
| 多 Agent 监督 | 需自定义（CodePilot 示例：`SupervisorAgent`） | LangGraph `supervisor` 模式 | CrewAI 核心能力 |

**翻译要点**：LangGraph 的 `StateGraph` + 条件边是 LangChain 生态中实现 PipeReAct 混合编排的最佳方式——Pipeline 路径用顺序边、ReAct 兜底用条件边。CrewAI 的编排能力集中在多 Agent 协作，单 Agent 的编排水位较弱。

### O 层 · 可观测性

| 概念 | AgentScope | LangChain | CrewAI |
|------|-----------|-----------|--------|
| Traces | CodePilot 教学实现：`TracerMiddleware`（埋点走 `GlobalOpenTelemetry.getTracer()`）→ OTLP | LangSmith / LangFuse | 仅控制台输出 |
| Metrics | OpenTelemetry + Micrometer → Prometheus（CodePilot 教学实现：`ObservabilityMiddleware` 旁路采集） | LangSmith Dashboard | 不支持 |
| 成本追踪 | 需自定义（CodePilot 示例：`CostAttributionMiddleware`） | LangSmith 自动计费 | 不支持 |
| 事件日志 | 需自定义（CodePilot 示例：`EventLogRecorder`） | LangSmith Runs | 不支持 |

**翻译要点**：LangChain 生态的可观测性高度依赖 LangSmith（商业产品）和 LangFuse（开源替代）。如果你不用 LangSmith，O 层需要自行搭建——Java 侧走 OpenTelemetry 标准（`GlobalOpenTelemetry.getTracer()`），结合 Micrometer 可实现自控的可观测性管线；CodePilot 的 `TracerMiddleware` 是这套做法的教学示例。

### V 层 · 验证与评估

| 概念 | AgentScope | LangChain | CrewAI |
|------|-----------|-----------|--------|
| LLM-as-Judge | CodePilot 教学实现：`LLMJudgeAdvisor` | LangChain `StringEvaluator` / `QAEvaluator` | 不支持 |
| 回归评估集 | 自定义 `RegressionEvaluator` | LangSmith Datasets + Experiments | 不支持 |
| EDD 流程 | CI 集成 + Eval Gate | LangSmith 原生支持 | 不支持 |

### G 层 · 治理与安全

| 概念 | AgentScope | LangChain | CrewAI |
|------|-----------|-----------|--------|
| 安全审查 | 需自定义（CodePilot 示例：`SafeGuardMiddleware`，入站/出站/工具） | LangChain `Guardrails` | 不支持 |
| YAML 策略管理 | CodePilot 教学实现：`ConstitutionValidator` + `constitution.yml` | 需自定义 | 需自定义 |
| 审计日志 | 自定义 WORM 存储 | LangSmith 审计 | 不支持 |


## C.11 Agent 框架选择决策树

> 用三个问题决定你的 Agent 技术栈，避免成为"框架选择不当是项目失败第一原因"（第 1 章框架选型）的统计数据。

### 决策树

```
Q1: 你的团队主力技术栈是什么？

├─ Java / Spring Boot
│   → AgentScope ── 全书首推，与现有 Spring 生态无缝集成
│     覆盖：T/C/L/O/V/G 全六层 + E 层自建
│
├─ Python
│   │
│   ├─ Q2: 你的 Agent 需要多 Agent 协作吗？
│   │   │
│   │   ├─ 需要 → CrewAI（多 Agent 编排核心能力）
│   │   │   覆盖：L（强）/T（中）/C（弱）
│   │   │   空白：E/O/V/G 需自建——参考本书第 4/8/9/10 章
│   │   │
│   │   └─ 不需要 → Q3: 你的编排需求是什么复杂度？
│   │       │
│   │       ├─ 简单的工具调用链 → LangChain
│   │       │   覆盖：T/C/L（基本）/O（LangSmith）
│   │       │   空白：E/V/G 需自建
│   │       │
│   │       ├─ 复杂的有状态图编排 → LangGraph
│   │       │   覆盖：L（强——StateGraph+检查点）/T/C
│   │       │   空白：E/V/G 需自建
│   │       │
│   │       └─ 低代码/可视化编排 → Dify / n8n
│   │           覆盖：L/T/O（可视化）
│   │           空白：E/C/V/G 需自建
│   │
│   └─ Go / TypeScript / .NET 等其他语言
│       → 自建 + 参考 ETCLOVG 七层设计
│          每层的最小配置见 16.6 节四阶段路线图
│          你不必用 AgentScope——但 ETCLOVG 的设计原则是通用的
```

### 如果你的框架被判定"空白格太多"

并不是说不能选它——是说你需要在选型时就清楚这些空白格存在，并在项目计划中预留填补时间。参考第 1 章框架覆盖表（KP 1.2.3）、C.10 节的 AgentScope ↔ LangChain 映射、以及第 16 章的阶段化投入指南，建立一个"填补空白格"的工作计划。

**常见空白格补法**：
- **E 层空白**：Docker 沙箱——独立于任何框架，任何语言都能调用 Docker SDK
- **V 层空白**：自建评估集 + LLM-as-Judge——Python 生态有 DeepEval（50+ 指标）、LangChain 有 StringEvaluator
- **G 层空白**：安全审查——至少实现 L1 正则过滤（语言无关），OWASP Top 10 for Agentic Applications 提供威胁清单
- **O 层空白**：LangFuse（开源，支持 Python/JS）+ OpenTelemetry——覆盖 Trace/Metrics


## C.12 术语表

| 术语 | 英文 | 定义 |
|------|------|------|
| Harness | Harness | 包裹 Agent 模型的七层工程基础设施，确保其可靠、安全、可观测地运行 |
| 绑定约束论点 | Binding Constraint Thesis | 本书核心论点：Agent 可靠性瓶颈不在模型，在 Harness 工程层 |
| ETCLOVG | — | 七层 Harness 框架的缩写：E(Execution), T(Tooling), C(Context), L(Lifecycle), O(Observability), V(Verification), G(Governance) |
| 上下文漂移 | Context Drift | Agent 在长任务中逐渐偏离原始目标——目标被新的副目标覆盖 |
| Harness 即假设 | Harness-as-Hypothesis | 每个 Middleware/沙箱/评估器封装了一个"模型会出错"的假设——假设需要被持续验证 |
| EDD | Eval-Driven Development | 评估驱动的 Agent 开发——每次变更基于评估数据，而非直觉 |
| Agent 幻觉 | Agent Hallucination | Agent 生成看似合理但实际错误的信息——多步任务幻觉率 15-30% |
| Middlewares | Middlewares | AgentScope 中的横切关注点组件，在每次 LLM 调用前后织入行为，等价于 Spring AOP 的 Advice |
| PDA判定矩阵 | PDA Decision Matrix | 感知(Perceive)-决策(Decide)-行动(Act)-自主性(Autonomy) 四维判定标准，用于区分 Agent/聊天机器人/RAG/工作流 |
| ReAct | Reasoning and Acting | 推理与行动交替进行的 Agent 推理范式，每步先思考再行动再观察，形成感知-决策-行动闭环 |
| PipeReAct | Pipeline + ReAct | Pipeline 骨架 + ReAct 肌肉——L 层混合编排方案，用稳定管道承载确定性步骤，用 ReAct 处理不确定节点 |
| Plan-Execute | Plan and Execute | 先规划后执行的 Agent 推理范式，通过静态计划管理将上下文膨胀从 O(N²) 降至 O(N) |
| ReWOO | Reason Without Observation | 先完整推理再批量执行的范式，通过分离规划与执行消除中间观测步骤，在大步数场景下成本显著优于 ReAct |
| ToT（思维树） | Tree of Thoughts | 多路径并行探索的推理范式，通过广度优先搜索和回溯机制探索多种推理路径 |
| 幻觉调用 | Hallucinatory Tool Call | Agent 生成不存在或参数错误的工具调用——Meta 生产事故中 LangChain 0.3.2 测得 12% 幻觉率 |
| 循环卡死 | Infinite Loop | Agent 在工具调用循环中无法退出——无步数限制的 Agent 可能在一次任务中消耗远超预期的 Token |
| 目标漂移 | Goal Drift | Agent 在长任务中逐渐偏离原始目标——目标被新的副目标覆盖 |
| 上下文腐烂 | Context Decay | 早期信息在长序列中被中间噪声稀释——虽然信息还在但模型"看不到了" |
| 涌现行为 | Emergent Behavior | 多 Agent 交互中出现的不可预测系统级行为——可能是有益的优化策略，也可能是危险的协作偏差 |
| MCP（Model Context Protocol） | Model Context Protocol | Anthropic 提出的标准化工具暴露协议——Agent 与工具之间的通信标准 |
| A2A（Agent-to-Agent） | Agent-to-Agent | Google 提出的 Agent 间通信协议——Agent 与 Agent 的协作标准 |
| Function Calling | Function Calling | LLM 原生能力——模型直接输出结构化函数调用而非自由文本，是工具调用的底层机制 |
| KV-cache | Key-Value Cache | Transformer 推理中存储已计算的 Key-Value 张量以复用前缀计算，是 Prefix Caching 的底层机制 |
| Prefix Caching | Prefix Caching | 缓存相同 prompt 前缀的 KV-cache 以跳过重复计算的推理优化技术——稳定前缀缓存命中可将成本降低 50%+ |
| OverlayFS | Overlay Filesystem | 联合文件系统——通过多层叠加实现写时复制(Copy-on-Write)，是 Docker 容器文件系统的底层技术 |
| CompositeFilesystem | Composite Filesystem | 三层文件系统中的组合层——把多个存储后端（本地盘/对象存储/只读基础层）组合成统一视图，配合 Overlay（写时复制）与 Projection（白名单投影）构成多租户沙箱文件架构（Ch4 §4.6） |
| Projection（工作区投影） | Workspace Projection | 将宿主机目录以只读方式映射到沙箱——Agent 可读项目代码但无法修改源文件 |
| LLM-as-Judge | LLM as Judge | 用 LLM 作为评估裁判——对 Agent 输出进行自动化质量评分，是 V 层评估的核心方法 |
| 回归测试集 | Regression Test Suite | 包含已知历史失败案例的测试集合——配合 LLM-as-Judge 检测 Agent 行为故障 |
| 燃烧率（Burn Rate） | Burn Rate | 已消耗预算百分比与已消耗时间/步数百分比的比值——burnRate > 1.0 表示消耗速度快于预期，> 1.2 触发降级，> 1.5 触发硬熔断 |
| 熔断器（Circuit Breaker） | Circuit Breaker | 当错误率或成本超过阈值时自动中止 Agent 执行的保护机制 |
| 错误预算（Error Budget） | Error Budget | SLO 允许的最大失败量——错误预算耗尽时触发功能冻结，优先修复可靠性而非发布新功能 |
| SLO/SLI | Service Level Objective / Indicator | SLI 是服务质量的量化指标（如 P99 延迟 < 5s），SLO 是基于 SLI 设定的可靠性目标（如 99.5% 成功率） |
| Canary 灰度发布 | Canary Deployment | 渐进式发布模式——5% → 30% → 100% 逐步扩大流量，每阶段观察指标达标后才进入下一阶段 |
| 声明式宪法（Constitution） | Declarative Constitution | 用声明式规则定义 Agent 的行为边界和不可违反原则，由 G 层强制执行 |
| 作业成本法（ABC） | Activity-Based Costing | 按实际活动（工具调用、LLM 推理、检索）归因成本而非按 Agent 实例均摊——精确到每次工具调用的 token 成本 |
| 五区制预算 | Five-Zone Budgeting | 上下文窗口 Token 预算的预防性分区：系统提示 10%、任务描述 5%、记忆检索 15%、工具历史 40%、自由空间 30%。工具历史膨胀最快、超限即摘要压缩；自由空间是模型推理的呼吸余量，不能占满（Ch6 KP 6.1.2） |
| CodePilot | CodePilot | 本书贯穿案例——从裸模型 42% 成功率通过七层 Harness 逐章叠加达到 82% 的编程 Agent |
| AgentScope | AgentScope | 阿里云开源的 Agent 应用开发框架（Apache 2.0，有 Java 与 Python 实现）——提供 `ReActAgent`、`MiddlewareBase`、`@Tool`/`Toolkit`、`Memory` 等核心抽象；沙箱、治理、评估等七层能力由工程侧在框架之上自建（本书 CodePilot 仓库即为 Java 侧教学实现） |
| SandboxClient | Sandbox Client | 沙箱客户端的统称——Agent 通过它在隔离环境中执行代码。CodePilot 教学实现按后端分为 `DockerSandboxClient`/`E2BSandboxClient`/`DaytonaSandboxClient`/`KubernetesSandboxClient`（配套仓库教学实现，非框架内置） |
| OverlayFSSnapshotManager | Overlay FS Snapshot Manager | CodePilot 教学实现（ch04）——基于 OverlayFS 的沙箱快照与回滚管理器，负责沙箱文件系统的快照、挂载与毫秒级回滚 |
| DynamicToolRegistry | Dynamic Tool Registry | CodePilot 教学实现（ch05）——工具/技能的动态发现与注册管理器，配合 `SemanticToolRouter` 完成大规模工具的预选与治理 |
| 五要素描述框架 | Five-Element Description Framework | 工具"描述"组件内部的五要素规范——①做什么 ②什么时候用 ③参数说明 ④返回值说明 ⑤注意事项——写在 `@Tool(description=...)` 中，是 T 层工具注册的核心设计规范（Ch5 KP 5.1.1） |


## C.13 实验数据集索引

| 数据集 | 来源 | 规模 | 章节 | 用途 |
|--------|------|------|------|------|
| SWE-bench Verified | Princeton/Stanford | 500 Python 任务 | Ch9, Ch17 | 编码 Agent 端到端评测 |
| SWE-bench Pro | Scale AI | 1,865 任务 (41 repos) | Ch9 | 防污染编码 Agent 评测 |
| SWE-bench++ | Turing Ent. | 500 公开任务 (9 语言) | Ch9 | 多语言高难度编码评测 |
| BFCL V4 | UC Berkeley | 2,000+ 测试用例 | Ch5 | 工具调用准确率评测 |
| MultiAgentBench | ACL 2025 | 多场景协作评测 | Ch7, Ch15 | 多 Agent 协调质量 |
| τ-bench | — | 多步工具使用任务 | Ch1, Ch2 | 工具型 Agent 可靠性 |
| AgentDojo | — | 提示注入安全评测 | Ch10 | Agent 安全防御评测 |
| AppWorld / OfficeBench | — | 长周期多步任务 | Ch4 | 长任务 Agent 评测 |

_（注：完整下载链接和配置指南见 `datasets/README.md` 文件）_


---

> 本速查表是 Harness 工程化的完整度审计工具，与附录 A（故障诊断）、附录 B（构建方案）构成三表体系：
> - **A 答故障**：出问题后怎么修
> - **B 答构建**：从零开始怎么建
> - **C 答就绪**：建好了能不能上线
>
> 三表使用顺序：B 构建阶段参考选型决策 → C.4 七层审计逐项核验 → C.3 上线门禁 go/no-go 决策 → A 上线后故障反查。建议项目进入工程试点阶段（阶段 2）时，先用 C.4 完整跑一遍七层审计，修复所有 P0 未勾选项后再进入规模推广；每次跨层变更后用 C.5 回归矩阵确认没有引入相邻层缺陷。
