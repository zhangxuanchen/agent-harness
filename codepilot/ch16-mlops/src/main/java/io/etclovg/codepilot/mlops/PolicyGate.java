package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 策略合规门禁。
 * <p>对应书中 Ch16 §16.3 —— 发布前的策略合规检查。
 * <p>校验候选版本是否满足安全策略、数据治理与审计要求，
 * 不合规则阻断发布。
 */
@Component
public class PolicyGate {

    private static final Logger log = LoggerFactory.getLogger(PolicyGate.class);

    /**
     * 执行策略门禁检查。
     *
     * @param violations 策略违规项列表
     * @return 门禁结果
     */
    public GateResult check(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            log.info("策略门禁通过: 无违规项");
            return GateResult.pass("Policy", 1.0);
        }
        log.warn("策略门禁失败: violations={}", violations);
        return GateResult.fail("Policy", "策略违规: " + String.join(", ", violations));
    }

    /**
     * 策略门禁（版本重载）：扫描候选版本的工具调用与输出内容，
     * 发现安全违规则硬阻断，临界违规告警不阻断。
     * <p>对应书中 §16.1.1 {@code GatePipelineOrchestrator} 调用签名
     * {@code policyGate.check(candidateVersion)}。
     *
     * @param candidate 候选版本
     * @return 门禁结果（PASS / CONDITIONAL_FAIL / HARD_FAIL）
     */
    public GateResult check(String candidate) {
        log.info("策略门禁（版本扫描）: candidate={}", candidate);
        // 教学桩：生产实现调用安全规则引擎扫描工具调用与输出内容
        List<String> violations = List.of(); // 桩：无违规
        return check(violations); // 委托已有实现
    }
}
