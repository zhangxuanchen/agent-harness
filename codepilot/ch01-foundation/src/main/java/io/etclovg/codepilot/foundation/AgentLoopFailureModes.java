package io.etclovg.codepilot.foundation;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Agent 循环三类失败模式
 * 对应书中 Ch2 §2.3.2 — 幻觉行动、循环卡死、目标漂移
 *
 * <p>三种失败模式的工程防御：
 * <ul>
 *   <li>幻觉行动：T 层工具白名单 + E 层执行前校验</li>
 *   <li>循环卡死：L 层最大步数限制 + 重复检测</li>
 *   <li>目标漂移：G 层目标锚点 + V 层目标完成度评估</li>
 * </ul>
 */
@Component
public class AgentLoopFailureModes {

    public enum FailureMode {
        HALLUCINATED_ACTION("幻觉行动", "AI 调用了不存在的工具或传入错误参数",
            "T层白名单校验 + E层执行前参数验证"),
        LOOP_DEATH("循环卡死", "AI 在同一思维链上反复循环不收敛",
            "L层最大步数限制 + 思维链去重检测"),
        GOAL_DRIFT("目标漂移", "AI 执行过程中偏离原始目标",
            "L层目标锚点 + V层目标完成度检查");

        private final String name;
        private final String description;
        private final String defense;

        FailureMode(String name, String description, String defense) {
            this.name = name;
            this.description = description;
            this.defense = defense;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getDefense() { return defense; }
    }

    private final Map<String, Integer> callHistory = new LinkedHashMap<>();
    private String originalGoal;

    /**
     * 检测循环卡死：连续 N 次相同调用则判定为死循环
     */
    public boolean detectLoopDeath(int maxRepeatCount) {
        if (callHistory.isEmpty()) return false;

        Map.Entry<String, Integer> lastEntry = null;
        for (Map.Entry<String, Integer> entry : callHistory.entrySet()) {
            lastEntry = entry;
        }
        return lastEntry != null && lastEntry.getValue() >= maxRepeatCount;
    }

    /**
     * 检测目标漂移：当前子任务与原始目标语义偏离
     */
    public boolean detectGoalDrift(String currentTask) {
        if (originalGoal == null) return false;
        return !currentTask.toLowerCase().contains(extractKeyword(originalGoal));
    }

    /**
     * 检测幻觉行动：工具名不在白名单中
     */
    public boolean detectHallucinatedAction(String toolName, Set<String> whitelist) {
        return !whitelist.contains(toolName);
    }

    public void recordCall(String toolName) {
        callHistory.merge(toolName, 1, Integer::sum);
    }

    public void setOriginalGoal(String goal) {
        this.originalGoal = goal;
    }

    public List<FailureMode> getAllModes() {
        return List.of(FailureMode.values());
    }

    private String extractKeyword(String text) {
        return text.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "").toLowerCase();
    }
}
