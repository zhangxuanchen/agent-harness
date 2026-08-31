package io.etclovg.codepilot.multiagent;

import java.util.Set;

/**
 * 第二层：工具级规则。对应书中 Ch15 §15.3.2 · 概念示例。
 */
public record ToolRule(
    String toolName,
    boolean readBeforeWrite,    // 写文件前必须先读（防误覆盖）
    Set<String> blockedCommands,// 危险命令：rm -rf, sudo, curl | sh
    Set<String> protectedPaths  // 危险路径：~/.ssh, /etc/, .env
) {}
