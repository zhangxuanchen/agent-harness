package io.etclovg.codepilot.casestudy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具验证中间件：案例研究中的工具调用验证
 * 对应书中 Ch18 —— Agent 工具调用验证实战
 */
@Component
public class ToolValidationMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ToolValidationMiddleware.class);

    private final Map<String, ValidationRule> rules = new ConcurrentHashMap<>();
    private final List<ValidationRecord> validationLog = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_LOG_SIZE = 1000;

    public ToolValidationMiddleware() {
        registerDefaultRules();
    }

    public ValidationResult validate(String toolName, Map<String, Object> parameters) {
        log.debug("[ToolValidationMiddleware] 开始验证: tool={}", toolName);

        List<RuleViolation> violations = new ArrayList<>();

        for (ValidationRule rule : rules.values()) {
            if (!rule.enabled()) continue;

            if (!rule.appliesTo().isEmpty() && !rule.appliesTo().contains(toolName)) {
                continue;
            }

            RuleViolation violation = applyRule(rule, toolName, parameters);
            if (violation != null) {
                violations.add(violation);
            }
        }

        boolean valid = violations.isEmpty();
        ValidationResult result = new ValidationResult(
                toolName, parameters, valid, violations,
                valid ? "验证通过" : "验证失败: " + violations.size() + " 个规则违反"
        );

        ValidationRecord record = new ValidationRecord(
                UUID.randomUUID().toString().substring(0, 8),
                toolName, valid, violations, Instant.now()
        );
        validationLog.add(record);
        if (validationLog.size() > MAX_LOG_SIZE) {
            validationLog.subList(0, validationLog.size() - MAX_LOG_SIZE).clear();
        }

        if (!valid) {
            log.warn("[ToolValidationMiddleware] 工具验证失败: tool={}, violations={}",
                    toolName, violations.size());
        }

        return result;
    }

    public void registerRule(ValidationRule rule) {
        rules.put(rule.id(), rule);
        log.info("[ToolValidationMiddleware] 注册规则: id={}, name={}", rule.id(), rule.name());
    }

    public Optional<ValidationRule> getRule(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public List<ValidationRecord> getValidationLog(int limit) {
        return validationLog.stream()
                .sorted(Comparator.comparing(ValidationRecord::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    private RuleViolation applyRule(ValidationRule rule, String toolName,
                                    Map<String, Object> params) {
        return switch (rule.type()) {
            case REQUIRED_PARAM -> {
                String paramName = (String) rule.config().get("paramName");
                if (paramName != null && !params.containsKey(paramName)) {
                    yield new RuleViolation(rule.id(), rule.name(),
                            "缺少必要参数: " + paramName);
                }
                yield null;
            }
            case PARAM_TYPE -> {
                String paramName = (String) rule.config().get("paramName");
                String expectedType = (String) rule.config().get("type");
                if (paramName != null && params.containsKey(paramName)) {
                    Object value = params.get(paramName);
                    if (!matchesType(value, expectedType)) {
                        yield new RuleViolation(rule.id(), rule.name(),
                                "参数 " + paramName + " 类型不匹配: 期望 " + expectedType);
                    }
                }
                yield null;
            }
            case PARAM_RANGE -> {
                String paramName = (String) rule.config().get("paramName");
                if (paramName != null && params.containsKey(paramName)) {
                    Object value = params.get(paramName);
                    if (value instanceof Number num) {
                        double min = ((Number) rule.config().getOrDefault("min", 0)).doubleValue();
                        double max = ((Number) rule.config().getOrDefault("max", Double.MAX_VALUE)).doubleValue();
                        if (num.doubleValue() < min || num.doubleValue() > max) {
                            yield new RuleViolation(rule.id(), rule.name(),
                                    "参数 " + paramName + " 超出范围 [" + min + ", " + max + "]");
                        }
                    }
                }
                yield null;
            }
            case MAX_LENGTH -> {
                String paramName = (String) rule.config().get("paramName");
                int maxLen = ((Number) rule.config().getOrDefault("maxLength", Integer.MAX_VALUE)).intValue();
                if (paramName != null && params.containsKey(paramName)) {
                    Object value = params.get(paramName);
                    if (value instanceof String str && str.length() > maxLen) {
                        yield new RuleViolation(rule.id(), rule.name(),
                                "参数 " + paramName + " 长度超出限制: " + str.length() + " > " + maxLen);
                    }
                }
                yield null;
            }
        };
    }

    private boolean matchesType(Object value, String expectedType) {
        if (value == null) return "null".equals(expectedType);
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true;
        };
    }

    private void registerDefaultRules() {
        registerRule(new ValidationRule("search-query-required",
                "搜索查询必填", ValidationRule.RuleType.REQUIRED_PARAM,
                Map.of("paramName", "query"), Set.of("search"), true));
        registerRule(new ValidationRule("calculate-expression-required",
                "计算表达式必填", ValidationRule.RuleType.REQUIRED_PARAM,
                Map.of("paramName", "expression"), Set.of("calculate"), true));
        registerRule(new ValidationRule("max-string-length",
                "字符串长度限制", ValidationRule.RuleType.MAX_LENGTH,
                Map.of("paramName", "content", "maxLength", 10000),
                Set.of("write", "search"), true));
    }

    public record ValidationRule(
            String id, String name, RuleType type,
            Map<String, Object> config, Set<String> appliesTo, boolean enabled
    ) {
        public enum RuleType {
            REQUIRED_PARAM, PARAM_TYPE, PARAM_RANGE, MAX_LENGTH
        }
    }

    public record ValidationResult(
            String toolName, Map<String, Object> parameters,
            boolean valid, List<RuleViolation> violations, String message
    ) {}

    public record RuleViolation(String ruleId, String ruleName, String detail) {}

    public record ValidationRecord(
            String id, String toolName, boolean valid,
            List<RuleViolation> violations, Instant timestamp
    ) {}
}