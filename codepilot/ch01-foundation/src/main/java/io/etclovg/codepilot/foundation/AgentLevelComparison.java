package io.etclovg.codepilot.foundation;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Agent 自主度分级 L0-L4 对比示例
 * 对应书中 Ch2 §2.1.1 — 从工具调用到全自主的五个级别
 *
 * <p>核心区别：每一级在前一级基础上增加一项能力：
 * <pre>
 * L0 工具调用 → +工具调用 → L1 条件触发 → +自主决策 → L2 单步自主
 *   → +多步规划 → L3 多步自主 → +目标分解 → L4 完全自主
 * </pre>
 */
@Component
public class AgentLevelComparison {

    public enum AutonomyLevel {
        L0("工具调用", "人工指定每一步，AI仅在单步内生成内容", 0),
        L1("条件触发", "AI按预设规则调用工具，但不自主选择", 1),
        L2("单步自主", "AI自主选择调用什么工具、何时调用", 2),
        L3("多步自主", "AI自主完成需多步工具调用的复杂任务", 3),
        L4("完全自主", "AI自主规划、执行、纠错、学习", 4);

        private final String name;
        private final String description;
        private final int level;

        AutonomyLevel(String name, String description, int level) {
            this.name = name;
            this.description = description;
            this.level = level;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public int getLevel() { return level; }
    }

    /**
     * L0: 纯工具调用 — 人类决定了每一步
     */
    public String executeL0(String userInput, String toolName) {
        return "L0执行: 使用[" + toolName + "]处理[" + userInput + "]";
    }

    /**
     * L1: 条件触发 — 按预设规则调用
     */
    public String executeL1(String userInput, Map<String, String> rules) {
        String matchedTool = rules.getOrDefault(userInput, "default_tool");
        return "L1执行: 根据规则[" + matchedTool + "]处理[" + userInput + "]";
    }

    /**
     * L2: 单步自主 — AI 自主选工具
     */
    public String executeL2(String userInput, List<String> availableTools) {
        String selectedTool = availableTools.get(new Random().nextInt(availableTools.size()));
        return "L2执行: AI自主选择[" + selectedTool + "]处理[" + userInput + "]";
    }

    /**
     * L3: 多步自主 — AI 自主多步规划
     */
    public List<String> executeL3(String userInput, List<String> availableTools) {
        List<String> steps = new ArrayList<>();
        steps.add("分析任务: " + userInput);
        steps.add("选择工具: " + availableTools.get(0));
        steps.add("执行操作");
        steps.add("验证结果");
        return steps;
    }

    /**
     * L4: 完全自主 — AI 自主规划+执行+纠错+学习
     */
    public Map<String, Object> executeL4(String userInput, List<String> availableTools) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> plan = List.of("理解目标", "制定计划", "执行步骤", "评估结果", "学习经验");
        result.put("plan", plan);
        result.put("goal", userInput);
        result.put("status", "completed");
        return result;
    }

    public AutonomyLevel getLevelByName(String name) {
        for (AutonomyLevel level : AutonomyLevel.values()) {
            if (level.name.equals(name)) return level;
        }
        return null;
    }

    /**
     * 判断系统跨过工作流/Agent分界线的最低级别
     */
    public boolean isAgentLevel(AutonomyLevel level) {
        return level.level >= 2;
    }
}
