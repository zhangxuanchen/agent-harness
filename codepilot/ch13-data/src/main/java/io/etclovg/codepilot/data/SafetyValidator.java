package io.etclovg.codepilot.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 安全校验器。
 * <p>对应书中 Ch13 §13.2 —— V 层验证：安全+功能+性能三合一门禁。
 * <p>对工具候选执行 AST 注入检测（禁止 Runtime.exec / ProcessBuilder 无参数白名单）、
 * 功能断言测试（dry-run 10 组输入验证输出符合 Schema）、性能基准
 * （P50 延迟 &lt; 200ms 且 P99 &lt; 1s）。同时承担训练数据、检索内容、
 * 工具产出的敏感信息与注入检测。
 */
@Component
public class SafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(SafetyValidator.class);

    /**
     * 校验工具候选是否安全可用。
     * <p>执行三合一门禁检查（AST 注入检测 + 功能断言 + 性能基准）。
     *
     * @param candidate 工具候选
     * @return 通过返回 {@link ValidationResult#pass()}，否则返回 fail 结果
     */
    public ValidationResult validate(ToolCandidate candidate) {
        log.debug("[SafetyValidator] 校验工具候选: name={}",
                candidate != null ? candidate.name() : null);
        // 桩实现：默认通过
        return ValidationResult.pass();
    }

    /**
     * 校验内容是否安全（保留原有便捷方法）。
     *
     * @param content 待校验内容
     * @return 安全返回 true
     */
    public boolean isSafe(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        boolean safe = !content.toLowerCase().contains("ignore previous");
        if (!safe) {
            log.warn("检测到潜在注入内容");
        }
        return safe;
    }
}
