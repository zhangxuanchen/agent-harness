package io.etclovg.codepilot.multiagent;

/**
 * 第一层：权限模式枚举。对应书中 Ch15 §15.3.2 · 概念示例。
 */
public enum PermissionMode {
    DEFAULT,     // 正常模式，工具调用按白名单放行
    EXPLORE,     // 探索模式，所有写操作自动拦截
    BYPASS,      // 无人值守模式，但危险路径保护仍然生效
    DONT_ASK     // 不弹窗，所有 ask 自动 deny
}
