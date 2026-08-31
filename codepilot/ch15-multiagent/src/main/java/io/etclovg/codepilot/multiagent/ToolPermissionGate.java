package io.etclovg.codepilot.multiagent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 三层闸门检查器。对应书中 Ch15 §15.3.2 · 概念示例。
 * <p>每次工具调用依次过三层：权限模式 → 工具规则 → 人工审批。
 * 任一层拦截即返回 deny，不继续后续检查。
 * <p>与第 10 章 G 层工具白名单互补——G 层管"能不能调这个工具"，
 * 本节管"这个角色的 Agent 调这个工具时需要什么前置条件"。
 */
@Component
public class ToolPermissionGate {
    private final Map<String, ToolRule> rules;  // 工具名 → 规则（生产中由配置注入）

    public ToolPermissionGate(Map<String, ToolRule> rules) {
        this.rules = rules;
    }

    public ApprovalResult check(String agentRole, String toolName,
                                Map<String, Object> args, PermissionMode mode) {
        // 第一层：权限模式
        if (mode == PermissionMode.EXPLORE && isWriteTool(toolName)) {
            return ApprovalResult.DENIED;  // Explorer 只读，写操作直接拒绝
        }

        // 第二层：工具级规则
        ToolRule rule = rules.get(toolName);
        if (rule != null) {
            // read-before-write 检查
            if (rule.readBeforeWrite() && !hasReadPath(args.get("path"))) {
                return ApprovalResult.DENIED;
            }
            // 危险命令检测
            String cmd = String.valueOf(args.getOrDefault("command", ""));
            if (rule.blockedCommands().stream().anyMatch(cmd::contains)) {
                return ApprovalResult.DENIED;
            }
            // 危险路径保护——即使 BYPASS 模式仍然弹窗
            String path = String.valueOf(args.getOrDefault("path", ""));
            if (rule.protectedPaths().stream().anyMatch(path::startsWith)) {
                return ApprovalResult.ASK;  // 危险路径：即使 BYPASS 也弹窗
            }
        }

        // 第三层：人工审批（仅高风险操作）
        if (isHighRisk(toolName, agentRole)) {
            return ApprovalResult.ASK;  // 触发 Human-in-the-Loop
        }

        return ApprovalResult.APPROVED;
    }

    // ---- 概念示例桩：生产中按工具元数据/历史调用记录判定 ----
    private boolean isWriteTool(String toolName) {
        Set<String> writeTools = Set.of("write_file", "edit_file", "shell_exec", "delete_file");
        return writeTools.contains(toolName);
    }

    private boolean hasReadPath(Object path) {
        // 概念示例：生产中查本次会话是否已读取过该 path
        return path != null;
    }

    private boolean isHighRisk(String toolName, String agentRole) {
        // 概念示例：高风险工具 + 非授权角色组合
        return "shell_exec".equals(toolName) && !"developer".equalsIgnoreCase(agentRole);
    }
}
