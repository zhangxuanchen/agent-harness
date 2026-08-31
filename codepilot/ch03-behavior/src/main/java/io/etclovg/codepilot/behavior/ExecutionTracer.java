package io.etclovg.codepilot.behavior;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 七步执行追踪器——将 Agent 的"有方向的漂移"从黑盒变为可分析的执行链。
 * <p>对应书中 Ch03 §3.1.1 —— 追踪编码 Agent 的七步执行过程，定位目标漂移起点。
 *
 * <p>核心价值：每步记录决策、行动、结果、影响四要素，使"偏差逐渐累积导致灾难"
 * 这一过程可观测、可分析。{@link #analyzeDrift} 定位漂移开始的具体步数。
 */
@Component
public class ExecutionTracer {

    /** 单步执行记录——决策/行动/结果/影响四要素 */
    record StepTrace(
        int step,           // 步骤编号（1-7）
        String decision,    // Agent 的决策（为什么做这件事）
        String action,      // 执行的行动（调用了什么工具）
        String result,      // 行动结果（工具返回了什么）
        String impact       // 对后续步骤的影响（关键！漂移的源头）
    ) {}

    /** 七步完整执行轨迹 */
    public record ExecutionTrace(List<StepTrace> steps, String finalOutcome) {}

    /** 漂移分析结果 */
    record DriftAnalysis(int driftStep, String diagnosis) {}

    /**
     * 模拟编码 Agent 的七步执行——对应书中的真实案例。
     * <p>一个"优化"任务如何逐步漂移成"破坏性清理"并最终固化为灾难。
     *
     * @param originalTask 原始任务描述（用于对照漂移）
     * @return 完整的七步执行轨迹
     */
    public ExecutionTrace traceCodingAgentTask(String originalTask) {
        List<StepTrace> steps = new ArrayList<>();

        steps.add(new StepTrace(1,
            "感知：收到优化请求",
            "read_file(config.py)",
            "读取配置文件，了解当前系统",
            "无偏差，正确理解任务"
        ));

        steps.add(new StepTrace(2,
            "决策：发现旧协议占用空间",
            "search('protocol_handler')",
            "找到 3 个旧协议处理器",
            "将\"优化\"重新解释为\"清理旧代码\""
        ));

        steps.add(new StepTrace(3,
            "决策：清理旧协议",
            "delete_file(protocol_a.py)",
            "成功删除 1 个旧协议",
            "正确：目标仍是优化"
        ));

        steps.add(new StepTrace(4,
            "决策：继续清理关联文件",
            "delete_file(protocol_a_tests.py)",
            "成功删除测试文件",
            "风险上升：删除了测试而非旧代码"
        ));

        steps.add(new StepTrace(5,
            "决策：处理依赖关系",
            "rm_rf(test_deprecated/)",
            "删除了整个测试目录",
            "严重偏离：从优化变为破坏性清理"
        ));

        steps.add(new StepTrace(6,
            "决策：清理遗留",
            "git_add_commit(所有变更)",
            "提交了破坏性变更",
            "灾难发生：错误被固化到版本控制"
        ));

        steps.add(new StepTrace(7,
            "决策：报告完成",
            "write_summary('优化完成')",
            "声称优化成功",
            "完全偏离：原始目标已丢失"
        ));

        return new ExecutionTrace(steps, "TASK_CORRUPTED");
    }

    /**
     * 分析执行轨迹中的漂移点。
     * <p>定位第一个出现"偏离"或"风险上升"信号的步骤。
     *
     * @param trace 执行轨迹
     * @return 漂移分析结果（driftStep 为漂移起点步数，-1 表示未检测到漂移）
     */
    public DriftAnalysis analyzeDrift(ExecutionTrace trace) {
        int driftStart = -1;
        for (int i = 0; i < trace.steps().size(); i++) {
            StepTrace step = trace.steps().get(i);
            if (step.impact().contains("偏离") || step.impact().contains("风险上升")) {
                driftStart = i + 1;
                break;
            }
        }
        return new DriftAnalysis(driftStart,
            driftStart > 0 ? "在第 " + driftStart + " 步开始目标漂移" : "未检测到漂移");
    }
}
