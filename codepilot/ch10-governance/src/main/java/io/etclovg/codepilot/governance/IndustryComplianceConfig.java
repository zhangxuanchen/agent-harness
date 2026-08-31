package io.etclovg.codepilot.governance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.function.Predicate;

/**
 * G 层 · 行业合规配置——同一套 SecurityCheckpoint + RiskAdaptiveGovernor，
 * 按行业配置不同的风险基线与合规要求。
 *
 * <p>三种行业模板：
 * <ul>
 *   <li>金融 SOX：基线风险 0.7 → HIGH 档，WORM 全量审计</li>
 *   <li>医疗 HIPAA：基线风险 0.9 → CRITICAL 档，双重 PHI 脱敏</li>
 *   <li>通用 General：基线风险 0.2 → NORMAL 档，基础四检查点</li>
 * </ul>
 *
 * <p>对应书中 Ch10 §KP 10.5.1 — 行业合规适配。
 */
@Configuration
public class IndustryComplianceConfig {

    /** 金融 SOX：高风险基线 + WORM 审计 */
    @Bean("financialProfile")
    public ComplianceProfile financialProfile(AuditLogAdvisor auditLog) {
        return new ComplianceProfile("SOX", 0.7,      // 基线风险 0.7 → HIGH 档
                Set.of("full_audit"),                 // 所有操作全量审计（AuditLogAdvisor Merkle 树承载 WORM 语义）
                content -> auditLog.verifyIntegrity().intact());  // Merkle 完整性校验
    }

    /** 医疗 HIPAA：极高风险基线 + 双重 PHI 脱敏 */
    @Bean("healthcareProfile")
    public ComplianceProfile healthcareProfile() {
        return new ComplianceProfile("HIPAA", 0.9,    // 基线 0.9 → CRITICAL 档
                Set.of("phi_mask_input", "phi_mask_output", "encrypted_storage"),
                this::verifyPhiMasked);
    }

    /** 通用：低风险基线 + 基础四检查点 */
    @Bean("generalProfile")
    public ComplianceProfile generalProfile() {
        return new ComplianceProfile("General", 0.2,  // 基线 0.2 → NORMAL 档
                Set.of("basic_checks"), this::basicCheckPassed);
    }

    /** PHI 脱敏校验（简化示意） */
    private boolean verifyPhiMasked(String content) {
        return content == null || !content.contains("PHI");
    }

    /** 基础检查通过（简化示意） */
    private boolean basicCheckPassed(String content) {
        return true;
    }

    /** 合规档案——行业名称、基线风险、合规要求、验证器 */
    public record ComplianceProfile(String name, double baselineRisk,
                                    Set<String> requirements,
                                    Predicate<String> verifier) {}
}
