package io.etclovg.codepilot.mlops;

/**
 * 变更风险等级。对应书中 Ch16 §16.1.1。
 * <p>用于第四道 Human Gate 的路由：HIGH 强制人工审批，MEDIUM/LOW 自动放行（PASS_AUTO）。
 */
public enum RiskLevel {
    LOW, MEDIUM, HIGH
}
