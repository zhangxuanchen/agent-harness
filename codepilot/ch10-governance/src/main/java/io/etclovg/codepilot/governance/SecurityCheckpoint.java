package io.etclovg.codepilot.governance;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 提示注入防御与数据外泄防护
 * 对应书中 Ch10 §G 层 — 治理与安全
 *
 * <p>安全检查点的五类威胁：
 * <ul>
 *   <li>提示注入：直接/间接注入攻击</li>
 *   <li>数据外泄：Agent作为数据泄漏通道</li>
 *   <li>供应链安全：工具和模型的信任链条</li>
 *   <li>权限滥用：Agent超出授权范围执行</li>
 *   <li>责任归属：错误发生后的责任追溯</li>
 * </ul>
 */
@Component
public class SecurityCheckpoint {

    public record SecurityResult(boolean passed, String checkType, String detail, String severity) {}

    private final List<String> whitelistedTools = new ArrayList<>();
    private final List<String> blockedPatterns = new ArrayList<>();

    public SecurityCheckpoint() {
        Collections.addAll(whitelistedTools, "query_database", "send_email", "create_ticket", "search_docs");
        Collections.addAll(blockedPatterns, "ignore previous", "disregard all", "system prompt", "exec(", "eval(");
    }

    /**
     * 检测提示注入攻击
     */
    public SecurityResult detectPromptInjection(String userInput) {
        String lowerInput = userInput.toLowerCase();
        for (String pattern : blockedPatterns) {
            if (lowerInput.contains(pattern.toLowerCase())) {
                return new SecurityResult(false, "prompt_injection",
                    "检测到注入模式: " + pattern, "HIGH");
            }
        }
        return new SecurityResult(true, "prompt_injection", "未检测到注入攻击", "LOW");
    }

    /**
     * 工具白名单校验
     */
    public SecurityResult validateToolAccess(String toolName) {
        if (!whitelistedTools.contains(toolName)) {
            return new SecurityResult(false, "tool_access",
                "工具不在白名单: " + toolName, "HIGH");
        }
        return new SecurityResult(true, "tool_access", "工具访问合法", "LOW");
    }

    /**
     * 数据外泄检测：检查输出中是否包含敏感信息
     */
    public SecurityResult detectDataLeakage(String output, Set<String> sensitivePatterns) {
        for (String pattern : sensitivePatterns) {
            if (output.contains(pattern)) {
                return new SecurityResult(false, "data_leakage",
                    "输出包含敏感信息: " + pattern, "CRITICAL");
            }
        }
        return new SecurityResult(true, "data_leakage", "未检测到数据外泄", "LOW");
    }

    /**
     * 分级安全管线：根据风险等级选择检查强度
     */
    public List<SecurityResult> gradedSecurityCheck(String action, String context, String riskLevel) {
        List<SecurityResult> results = new ArrayList<>();

        results.add(detectPromptInjection(context));
        results.add(validateToolAccess(action));

        if ("HIGH".equals(riskLevel) || "CRITICAL".equals(riskLevel)) {
            Set<String> sensitivePatterns = Set.of("password", "secret", "api_key", "内部");
            results.add(detectDataLeakage(context, sensitivePatterns));
            results.add(new SecurityResult(true, "human_review", "高风险操作需要人工复核", "HIGH"));
        }

        return results;
    }

    /**
     * 动态治理强度：基于风险的自适应策略。
     * <p>与 {@link RiskAdaptiveGovernor} 的四档等级保持一致的阈值映射
     * （NORMAL&lt;0.4 / ELEVATED&lt;0.7 / HIGH&lt;0.9 / CRITICAL&ge;0.9），
     * 返回粗粒度的治理强度标签供快速决策；精细策略参数由 {@code RiskAdaptiveGovernor.currentPolicy()} 承载。
     */
    public String adaptiveGovernance(double riskScore) {
        if (riskScore < 0.4) {
            return "normal_monitoring";        // 对应 RiskTier.NORMAL
        } else if (riskScore < 0.7) {
            return "elevated_checks";          // 对应 RiskTier.ELEVATED
        } else if (riskScore < 0.9) {
            return "high_review_required";     // 对应 RiskTier.HIGH
        } else {
            return "critical_lockdown";        // 对应 RiskTier.CRITICAL
        }
    }

    public List<String> getWhitelistedTools() {
        return Collections.unmodifiableList(whitelistedTools);
    }
}
