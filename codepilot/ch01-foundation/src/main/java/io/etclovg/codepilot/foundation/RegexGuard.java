package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正则守卫：基于正则表达式的输入安全守卫
 * 对应书中 Ch01 §1.3 —— 输入验证与安全防线
 */
@Component
public class RegexGuard {

    private static final Logger log = LoggerFactory.getLogger(RegexGuard.class);

    private final Map<String, GuardRule> rules = new ConcurrentHashMap<>();
    private final List<String> violationLog = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_VIOLATION_LOG = 500;

    public RegexGuard() {
        registerDefaultRules();
    }

    public GuardResult check(String input) {
        if (input == null || input.isBlank()) {
            return new GuardResult(true, "输入为空，跳过检查", List.of());
        }

        List<RuleViolation> violations = new ArrayList<>();
        for (Map.Entry<String, GuardRule> entry : rules.entrySet()) {
            GuardRule rule = entry.getValue();
            if (!rule.enabled()) continue;

            boolean matched = rule.pattern().matcher(input).find();
            if (rule.type() == GuardRule.RuleType.BLOCK && matched) {
                violations.add(new RuleViolation(rule.id(), rule.name(), "检测到禁止模式: " + rule.pattern().pattern()));
            } else if (rule.type() == GuardRule.RuleType.REQUIRE && !matched) {
                violations.add(new RuleViolation(rule.id(), rule.name(), "缺少必要模式: " + rule.pattern().pattern()));
            }
        }

        if (!violations.isEmpty()) {
            String logEntry = String.format("[RegexGuard] 输入被拦截: violations=%d, inputPreview=%s",
                    violations.size(), input.substring(0, Math.min(100, input.length())));
            violationLog.add(logEntry);
            if (violationLog.size() > MAX_VIOLATION_LOG) {
                violationLog.subList(0, violationLog.size() - MAX_VIOLATION_LOG).clear();
            }
            log.warn(logEntry);
        }

        return new GuardResult(violations.isEmpty(),
                violations.isEmpty() ? "检查通过" : "检测到 " + violations.size() + " 个违规",
                violations);
    }

    public void registerRule(GuardRule rule) {
        rules.put(rule.id(), rule);
        log.info("[RegexGuard] 注册规则: id={}, name={}, type={}",
                rule.id(), rule.name(), rule.type());
    }

    public boolean removeRule(String ruleId) {
        GuardRule removed = rules.remove(ruleId);
        if (removed != null) {
            log.info("[RegexGuard] 移除规则: id={}", ruleId);
            return true;
        }
        return false;
    }

    public List<String> getViolationLog() {
        return Collections.unmodifiableList(violationLog);
    }

    private void registerDefaultRules() {
        registerRule(new GuardRule("sql-injection", "SQL注入检测",
                Pattern.compile("(?i)(\\b(SELECT|INSERT|UPDATE|DELETE|DROP|UNION|EXEC|EXECUTE)\\b.*\\b(FROM|INTO|TABLE|WHERE)\\b)"),
                GuardRule.RuleType.BLOCK, true));

        registerRule(new GuardRule("path-traversal", "路径穿越检测",
                Pattern.compile("(\\.\\./|\\.\\.\\\\|%2e%2e%2f)"),
                GuardRule.RuleType.BLOCK, true));

        registerRule(new GuardRule("command-injection", "命令注入检测",
                Pattern.compile("(?i)(\\b(cat|ls|rm|chmod|chown|wget|curl|bash|sh|cmd|powershell)\\b.*[;&|`$])"),
                GuardRule.RuleType.BLOCK, true));

        registerRule(new GuardRule("sensitive-data", "敏感数据检测",
                Pattern.compile("(?i)(password\\s*[=:]|secret\\s*[=:]|api[_-]?key\\s*[=:]|token\\s*[=:])"),
                GuardRule.RuleType.BLOCK, true));

        registerRule(new GuardRule("max-length", "最大长度限制",
                Pattern.compile("^.{1,500}$"),
                GuardRule.RuleType.REQUIRE, true));

        log.info("[RegexGuard] 默认规则注册完成, ruleCount={}", rules.size());
    }

    public record GuardRule(
            String id, String name, Pattern pattern, RuleType type, boolean enabled
    ) {
        public enum RuleType {
            BLOCK,
            REQUIRE
        }

        public GuardRule withEnabled(boolean enabled) {
            return new GuardRule(id, name, pattern, type, enabled);
        }
    }

    public record GuardResult(boolean passed, String message, List<RuleViolation> violations) {}

    public record RuleViolation(String ruleId, String ruleName, String detail) {}
}