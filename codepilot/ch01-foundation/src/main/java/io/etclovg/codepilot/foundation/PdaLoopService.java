package io.etclovg.codepilot.foundation;

/*
 * ⚠️ SKELETON ONLY: This module is a structural placeholder for the chapter's architecture.
 * Not runnable. It exists to show the class structure and method signatures described in the book.
 * For production implementation, refer to the corresponding chapters in the book and the
 * runnable modules in ch04/ch05/ch06/ch07/ch15/ch16/ch17.
 */

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * PDA 闭环服务：Perceive-Decide-Act 循环
 * 展示 Agent 的感知→决策→行动→反馈闭环
 *
 * <p>接入 {@link AgentLoopFailureModes} 三类失败模式检测（对应书中 Ch2 §2.3.2）：
 * <ul>
 *   <li>幻觉行动：决策阶段校验工具是否在 T 层白名单内</li>
 *   <li>循环卡死：行动阶段记录调用并检测连续重复</li>
 *   <li>目标漂移：每步检查当前决策与原始目标的语义偏离</li>
 * </ul>
 * 检测到任一失败模式时记录防御策略并中断循环。
 */
@Service
public class PdaLoopService {

    private static final Logger log = LoggerFactory.getLogger(PdaLoopService.class);
    private static final int MAX_STEPS = 20;
    private static final int MAX_REPEAT_COUNT = 3;

    /** T 层工具白名单——对应书中 Ch2 §2.3.2 幻觉行动防御 */
    private static final Set<String> TOOL_WHITELIST = Set.of(
        "search", "calculate", "fetch", "write", "verify"
    );

    private final AgentLoopFailureModes failureModes;

    public PdaLoopService(AgentLoopFailureModes failureModes) {
        this.failureModes = failureModes;
    }

    public PdaResult runTask(String task) {
        log.info("=== PDA Loop Start: {} ===", task);
        failureModes.setOriginalGoal(task);

        String context = task;
        int steps = 0;
        boolean completed = false;
        String failureReason = null;

        while (steps < MAX_STEPS && !completed && failureReason == null) {
            steps++;
            log.info("--- Step {} ---", steps);

            // Perceive: 感知当前状态
            String perception = perceive(context);
            log.info("Perceive: {}", perception);

            // Decide: 决策下一步行动
            String decision = decide(perception, steps);
            log.info("Decide: {}", decision);
            String toolName = extractToolName(decision);

            // 失败模式检测 1：幻觉行动——工具是否在白名单内
            if (failureModes.detectHallucinatedAction(toolName, TOOL_WHITELIST)) {
                failureReason = AgentLoopFailureModes.FailureMode.HALLUCINATED_ACTION.getName()
                    + ": 工具[" + toolName + "]不在白名单" + TOOL_WHITELIST + "中";
                log.warn("⚠️ 检测到失败模式: {}", failureReason);
                log.warn("  防御策略: {}", AgentLoopFailureModes.FailureMode.HALLUCINATED_ACTION.getDefense());
                break;
            }

            // Act: 执行行动
            String result = act(decision, steps);
            log.info("Act: {}", result);

            // 失败模式检测 2：循环卡死——记录调用并检测连续重复
            failureModes.recordCall(toolName);
            if (failureModes.detectLoopDeath(MAX_REPEAT_COUNT)) {
                failureReason = AgentLoopFailureModes.FailureMode.LOOP_DEATH.getName()
                    + ": 工具[" + toolName + "]连续调用超过" + MAX_REPEAT_COUNT + "次";
                log.warn("⚠️ 检测到失败模式: {}", failureReason);
                log.warn("  防御策略: {}", AgentLoopFailureModes.FailureMode.LOOP_DEATH.getDefense());
                break;
            }

            // 失败模式检测 3：目标漂移——当前决策是否偏离原始目标
            if (failureModes.detectGoalDrift(decision)) {
                failureReason = AgentLoopFailureModes.FailureMode.GOAL_DRIFT.getName()
                    + ": 决策[" + decision + "]偏离原始目标[" + task + "]";
                log.warn("⚠️ 检测到失败模式: {}", failureReason);
                log.warn("  防御策略: {}", AgentLoopFailureModes.FailureMode.GOAL_DRIFT.getDefense());
                break;
            }

            // Feedback: 反馈结果到上下文
            context = context + "\n[Step " + steps + "] " + decision + " -> " + result;

            completed = checkCompletion(result);
        }

        if (failureReason != null) {
            log.warn("=== PDA Loop Aborted: {} steps, reason={} ===", steps, failureReason);
        } else {
            log.info("=== PDA Loop End: {} steps, completed={} ===", steps, completed);
        }
        return new PdaResult(steps, completed, context, failureReason);
    }

    private String perceive(String context) {
        return "感知上下文状态: 长度=" + context.length() + " chars";
    }

    /**
     * 模拟多步推理：search → calculate → verify → write
     * 不同步骤使用不同工具，避免循环卡死误触发
     */
    private String decide(String perception, int step) {
        String tool = switch (step) {
            case 1 -> "search";
            case 2 -> "calculate";
            case 3 -> "verify";
            default -> "write";
        };
        return "决策: 调用工具[" + tool + "]处理";
    }

    /**
     * 模拟多步执行，前 3 步不完成，第 4 步完成
     * 让循环多执行几步，失败模式检测才有机会触发
     */
    private String act(String decision, int step) {
        if (step < 4) {
            return "执行中... step=" + step;
        }
        return "行动完成";
    }

    private boolean checkCompletion(String result) {
        return result.contains("完成") || result.contains("done");
    }

    /** 从决策字符串中提取工具名，格式如 "调用工具[search]..." */
    private String extractToolName(String decision) {
        int start = decision.indexOf('[');
        int end = decision.indexOf(']');
        if (start >= 0 && end > start) {
            return decision.substring(start + 1, end);
        }
        return "unknown";
    }

    }
