package io.etclovg.codepilot.behavior;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CodePilot 骨架检测器。
 * <p>对应书中 Ch03 §3.5 —— CodePilot 的初始版本：一个最小可运行、故意留六个缺口的 Agent，
 * 作为后续七章逐层加固的起点。
 *
 * <p>六个缺口（对应 ETCLOVG 六层缺失）：
 * <ol>
 *   <li>E 层无容器隔离（进程级执行）</li>
 *   <li>T 层工具描述粗糙（2 个手写 SQL 工具）</li>
 *   <li>C 层无记忆截断（全量历史膨胀）</li>
 *   <li>L 层无步数限制（ReAct 无限循环）</li>
 *   <li>O 层无结构化 trace（仅控制台日志）</li>
 *   <li>G 层零安全防护（可 DROP TABLE）</li>
 * </ol>
 */
@Component
public class CodePilotSkeleton {

    /**
     * 骨架检测结果（保留旧 API 以兼容测试）。
     */
    public record SkeletonCheckResult(
            boolean isSkeleton,
            int implementedFeatures,
            int totalFeatures,
            double completionRate
    ) {}

    /**
     * 骨架的缺口清单记录——后续章节逐个修复。
     */
    public record SkeletonGapsRecord(List<String> unfixed) {}

    /**
     * 执行骨架检测（保留旧 API）。
     */
    public SkeletonCheckResult check(int implemented, int total) {
        double rate = total > 0 ? (double) implemented / total : 0;
        return new SkeletonCheckResult(rate < 0.3, implemented, total, rate);
    }

    /**
     * 返回当前骨架的缺口清单——每个缺口对应后续一章的修复目标。
     */
    public SkeletonGapsRecord currentGaps() {
        return new SkeletonGapsRecord(List.of(
            "E: 无容器隔离 → Ch4 加 Docker 沙箱",
            "T: 工具描述粗糙 → Ch5 加结构化 Schema",
            "C: 无记忆截断 → Ch6 加三层记忆",
            "L: 无步数限制 → Ch7 加 MaxSteps",
            "O: 无结构化 trace → Ch8 加 OpenTelemetry",
            "G: 零安全防护 → Ch10 加四钩子治理"
        ));
    }

    /**
     * CodePilot 骨架入口——对应书中 Ch03 §3.5.1 的 42% 基线。
     *
     * <p>故意留六个缺口的可运行 Agent，作为第 4-10 章逐层加固的起点：
     * <ul>
     *   <li>缺口 1: E 层无容器（进程级执行）</li>
     *   <li>缺口 2: T 层工具描述粗糙（2 个手写 SQL 工具）</li>
     *   <li>缺口 3: C 层无记忆截断（全量历史膨胀）</li>
     *   <li>缺口 4: L 层无步数限制（ReAct 无限循环）</li>
     *   <li>缺口 5: O 层无结构化 trace（仅控制台日志）</li>
     *   <li>缺口 6: G 层零安全防护（可 DROP TABLE）</li>
     * </ul>
     *
     * <p>后续每章修复一个缺口，验证该层的独立贡献。运行需提供 {@code DASHSCOPE_API_KEY}
     * 环境变量；模型默认 {@code dashscope:qwen-plus}，可通过 {@code DASHSCOPE_MODEL} 覆盖。
     */
    public static void main(String[] args) {
        String model = System.getenv().getOrDefault(
            "DASHSCOPE_MODEL", "dashscope:qwen-plus");

        // ⚠ 缺口 2: 手写 SQL 工具，无结构化描述
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DatabaseSkeletonTool());  // @Tool 方法：execute_sql / query_schema

        ReActAgent agent = ReActAgent.builder()
            .model(model)
            .toolkit(toolkit)
            // ⚠ 缺口 3: 无记忆截断，全量历史可能超 token 限制
            .middlewares(List.of(
                new AgentStateMiddleware(
                    new InMemoryAgentStateRepository(), "default", Integer.MAX_VALUE)))
            // ⚠ 缺口 4: 无步数限制，ReAct 可能无限循环
            // ⚠ 缺口 5: 无结构化 trace，仅靠 System.out.println
            // ⚠ 缺口 6: 零安全防护，无法阻止 DROP TABLE 等危险操作
            .build();

        // ⚠ 缺口 1: 无容器隔离，直接在宿主机执行 SQL
        String result = agent.call("查询所有用户的订单总额", RuntimeContext.empty())
            .block().getTextContent();

        System.out.println("[CodePilot-Skeleton] " + result);
    }
}
