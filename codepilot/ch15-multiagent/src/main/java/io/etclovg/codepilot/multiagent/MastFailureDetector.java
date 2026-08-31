package io.etclovg.codepilot.multiagent;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MAST 14 种协调失败模式的在线检测 + 预防触发 + 恢复动作。对应书中 Ch15 §15.5.2 · 概念示例。
 * <p>对每个被检测到的失败模式返回 {@link FailureResponse}（预防 + 恢复 + 证据来源）。
 * MAST 失败常并发（如步骤重复 + 历史丢失），故返回 Map 而非单一结果；空集表示未检测到失败。
 *
 * <p><b>诚实说明</b>：MAST 论文（arXiv:2503.13657）做的是离线标注分析，本检测器是其运行时外推——
 * 部分模式可机械检测（步骤重复 / 过早终止 / 历史丢失），部分需 LLM Judge 或规范匹配
 * （违背任务规范 / 任务脱轨 / 推理-行动失配，成本更高）。恢复策略为工程补充，非论文原文。
 */
@Component
public class MastFailureDetector {

    public Map<MastFailureMode, FailureResponse> detectAndRecover(
            AgentTask task, Map<String, Object> runtimeCtx) {
        Map<MastFailureMode, FailureResponse> actions = new LinkedHashMap<>();

        // ---- FM-1.3 步骤重复（15.7%，最高）——机械可检：同签名 ≥2 次 ----
        @SuppressWarnings("unchecked")
        List<String> callSignatures = (List<String>) runtimeCtx.getOrDefault("toolCallSignatures", List.of());
        if (hasDuplicate(callSignatures)) {
            actions.put(MastFailureMode.STEP_REPETITION, new FailureResponse(
                "追踪工具调用签名，N 轮内重复即标记",
                "回滚最后一步，跳过已尝试路径重试",
                FailureResponse.Evidence.ENGINEERING));
        }

        // ---- FM-2.6 推理-行动失配（13.2%）——LLM Judge：意图 vs 实际调用 ----
        if (runtimeCtx.containsKey("intentActionMismatch")) {
            actions.put(MastFailureMode.REASONING_ACTION_MISMATCH, new FailureResponse(
                "工具调用前强制声明 INTENT，运行时比对",
                "该步降级为单 Agent 重做",
                FailureResponse.Evidence.ENGINEERING));
        }

        // ---- FM-1.5 不知终止条件（12.4%）——机械可检：brief 缺 TERMINATION 段 ----
        if (!task.terminationCondition()) {
            actions.put(MastFailureMode.UNSURE_OF_TERMINATION, new FailureResponse(
                "brief 必含 TERMINATION: 段声明完成信号",
                "外部终止器/超时强制收尾，取当前最优兜底",
                FailureResponse.Evidence.ENGINEERING));
        }

        // ---- FM-1.1 违背任务规范（11.8%）——LLM Judge：输出 vs 原始要求 ----
        if (runtimeCtx.containsKey("deviationFromTaskSpec")) {
            actions.put(MastFailureMode.DISOBEY_TASK_SPEC, new FailureResponse(
                "brief 逐字重述用户原始要求",
                "用收紧规范重新分解该子任务",
                FailureResponse.Evidence.ENGINEERING));
        }

        // ---- FM-3.3 错误验证（9.1%）——对抗式复核发现"通过验证的遗漏" ----
        if (runtimeCtx.containsKey("adversarialFoundGap")) {
            actions.put(MastFailureMode.INCORRECT_VERIFICATION, new FailureResponse(
                "对抗式 Reviewer 复核通过的验证",
                "第二 Judge 重新验证，不一致则重做",
                FailureResponse.Evidence.ENGINEERING));
        }

        // ---- FM-3.2 无/不完整验证（8.2%）——机械可检：未调用 verifier ----
        if (!runtimeCtx.containsKey("verifierInvoked")) {
            actions.put(MastFailureMode.NO_VERIFICATION, new FailureResponse(
                "强制多级验证（类型→单测→集成→行为）；论文 +15.6pp",
                "补跑缺失验证层级，失败则回滚",
                FailureResponse.Evidence.PAPER));
        }

        // ---- FM-3.1 过早终止（6.2%）——机械可检：complete 但验证未过 ----
        if (Boolean.TRUE.equals(runtimeCtx.get("declaredComplete"))
                && !Boolean.TRUE.equals(runtimeCtx.get("verificationPassed"))) {
            actions.put(MastFailureMode.PREMATURE_TERMINATION, new FailureResponse(
                "终态必含 status 字段，complete 须通过验证",
                "拒绝终态声明，退回继续执行",
                FailureResponse.Evidence.ENGINEERING));
        }

        // （其余 7 种低频模式——任务脱轨/未请求澄清/丢失历史/对话重置/
        //   忽略他方输入/违背角色规范/信息隐瞒——检测规则结构相同，略）
        return actions;
    }

    private boolean hasDuplicate(List<String> sigs) {
        return new HashSet<>(sigs).size() < sigs.size();
    }
}
