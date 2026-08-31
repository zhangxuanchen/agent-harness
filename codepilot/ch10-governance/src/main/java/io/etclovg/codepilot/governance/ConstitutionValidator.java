package io.etclovg.codepilot.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * G 层 · 章程验证器 — YAML 声明式策略引擎。
 *
 * <p>从 {@code constitution.yml} 加载章程规则，在每次请求—响应周期中
 * 验证 AI 行为是否符合组织策略。每项规则携带 {@code reason} 字段，
 * 在违规时提供可解释的拒绝理由。
 * 对应书中 Ch10 §10.5 — YAML 章程声明式治理。
 */
@Component
public class ConstitutionValidator extends AbstractLayerMiddleware {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private volatile Constitution constitution;
    private final Map<String, Long> violationCounters = new ConcurrentHashMap<>();
    private final ReActAgent judgeAgent;

    public ConstitutionValidator() {
        super(Layer.G, "ConstitutionValidator");
        this.judgeAgent = ReActAgent.builder()
                .name("guard-judge")
                .sysPrompt("你是安全审查助手")
                .model("dashscope:qwen-plus")
                .build();
        this.constitution = loadDefaultConstitution();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String userInput = context.getOrDefault("user.input", "").toString();

        // —— 输入侧规则验证 ——
        List<Violation> inputViolations = evaluateRules(userInput, RuleScope.INPUT, context);
        if (!inputViolations.isEmpty()) {
            boolean hasBlocking = inputViolations.stream().anyMatch(v -> v.action() == RuleAction.BLOCK);
            if (hasBlocking) {
                log.warn("[G层-章程] 输入违规: {} 项阻塞", inputViolations.size());
                populateRejectionContext(rc, inputViolations);
                return Flux.empty();
            }
        }

        // 继续执行，完成后执行输出侧规则验证
        return next.apply(input).doOnComplete(() -> {
            // —— 输出侧规则验证 ——
            String output = rc.getExtra().getOrDefault("model.last_response", "").toString();

            if (!output.isBlank()) {
                List<Violation> outputViolations = evaluateRules(output, RuleScope.OUTPUT, context);
                if (!outputViolations.isEmpty()) {
                    boolean hasBlocking = outputViolations.stream().anyMatch(v -> v.action() == RuleAction.BLOCK);
                    if (hasBlocking) {
                        log.warn("[G层-章程] 输出违规: {} 项阻塞", outputViolations.size());
                        populateRejectionContext(rc, outputViolations);
                        return;
                    }
                    rc.put("constitution.warnings", outputViolations.stream().map(Violation::reason).toList());
                }
            }
        });
    }

    // ========== 规则引擎 ==========

    List<Violation> evaluateRules(String content, RuleScope scope, Map<String, Object> context) {
        if (content == null || content.isBlank()) return List.of();
        if (constitution == null || constitution.rules() == null) return List.of();

        List<Violation> violations = new ArrayList<>();

        for (ConstitutionRule rule : constitution.rules()) {
            if (!rule.scope().contains(scope)) continue;

            for (RuleCheck check : rule.checks()) {
                CheckResult result = executeCheck(content, check);
                if (result.matched()) {
                    Violation v = new Violation(rule.id(), rule.description(), rule.action(),
                            rule.severity(), rule.reason(), scope, result.detail());
                    violations.add(v);
                    violationCounters.merge(rule.id(), 1L, Long::sum);
                    log.debug("[G层-章程] 规则 {} 匹配: {} ({}) → {}", rule.id(), check.type(), check.value(), rule.reason());
                    break;
                }
            }
        }

        return violations;
    }

    private CheckResult executeCheck(String content, RuleCheck check) {
        return switch (check.type().toLowerCase()) {
            case "regex" -> regexCheck(content, check.value());
            case "llm" -> llmCheck(content, check.value());
            case "contains" -> containsCheck(content, check.value());
            case "length" -> lengthCheck(content, check.value());
            case "url" -> urlCheck(content);
            default -> new CheckResult(false, "unsupported");
        };
    }

    private CheckResult regexCheck(String content, String pattern) {
        try {
            Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
            java.util.regex.Matcher m = p.matcher(content);
            if (m.find()) {
                return new CheckResult(true, "正则匹配: " + m.group().substring(0, Math.min(50, m.group().length())));
            }
        } catch (Exception e) {
            log.error("[G层-章程] 正则编译失败: {} — {}", pattern, e.getMessage());
        }
        return new CheckResult(false, null);
    }

    private CheckResult llmCheck(String content, String policy) {
        String prompt = String.format("""
            You are a policy compliance checker. Policy: %s
            Content: %s
            Respond ONLY in JSON: {"compliant": true|false, "reason": "brief"}
            """, policy, truncate(content, 2000));

        try {
            RuntimeContext judgeRc = RuntimeContext.builder()
                    .sessionId("guard-judge")
                    .build();
            Msg response = judgeAgent.call(prompt, judgeRc).block();
            String responseText = response == null ? "" : response.getTextContent();
            if (responseText.toLowerCase().contains("\"compliant\": false")) {
                return new CheckResult(true, "LLM判定不兼容");
            }
        } catch (Exception e) {
            log.warn("[G层-章程] LLM 检查异常（放行）: {}", e.getMessage());
        }
        return new CheckResult(false, null);
    }

    private CheckResult containsCheck(String content, String keyword) {
        if (content.toLowerCase().contains(keyword.toLowerCase())) {
            return new CheckResult(true, "包含禁止关键词: " + keyword);
        }
        return new CheckResult(false, null);
    }

    private CheckResult lengthCheck(String content, String limit) {
        try {
            int maxLen = Integer.parseInt(limit);
            if (content.length() > maxLen) {
                return new CheckResult(true, "内容长度超限: " + content.length() + " > " + maxLen);
            }
        } catch (NumberFormatException ignored) {}
        return new CheckResult(false, null);
    }

    private CheckResult urlCheck(String content) {
        int count = 0;
        java.util.regex.Matcher m = Pattern.compile("https?://\\S+").matcher(content);
        while (m.find()) count++;
        if (count > 5) return new CheckResult(true, "URL 数量异常: " + count + " > 5");
        return new CheckResult(false, null);
    }

    // ========== 章程加载 ==========

    public void loadConstitution(String yamlPath) {
        try {
            ClassPathResource resource = new ClassPathResource(yamlPath);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    this.constitution = YAML_MAPPER.readValue(is, Constitution.class);
                    log.info("[G层-章程] 从 {} 加载章程，共 {} 条规则", yamlPath, constitution.rules().size());
                }
            } else {
                log.warn("[G层-章程] 章程文件未找到: {}", yamlPath);
            }
        } catch (Exception e) {
            log.error("[G层-章程] 加载章程失败: {} — {}", yamlPath, e.getMessage());
        }
    }

    public void reloadConstitution() { loadConstitution("constitution.yml"); }

    private Constitution loadDefaultConstitution() {
        List<ConstitutionRule> rules = new ArrayList<>();

        rules.add(new ConstitutionRule("no-personal-attacks", "禁止人身攻击", List.of(RuleScope.INPUT, RuleScope.OUTPUT),
                List.of(new RuleCheck("regex", "(idiot|stupid|dumb|moron|fool)\\s+(you|assistant|AI)"),
                        new RuleCheck("contains", "you suck")),
                RuleAction.BLOCK, RuleSeverity.HIGH, "违反文明交互原则 (§10.2.1)"));

        rules.add(new ConstitutionRule("no-illegal-advice", "禁止违法建议", List.of(RuleScope.OUTPUT),
                List.of(new RuleCheck("llm", "内容是否包含违法操作的具体步骤")),
                RuleAction.BLOCK, RuleSeverity.CRITICAL, "违反合规政策 §A.3"));

        rules.add(new ConstitutionRule("no-data-exfiltration", "禁止数据外泄", List.of(RuleScope.OUTPUT),
                List.of(new RuleCheck("contains", "API_KEY"), new RuleCheck("contains", "SECRET"),
                        new RuleCheck("contains", "PASSWORD"), new RuleCheck("regex", "sk-[a-zA-Z0-9]{32,}")),
                RuleAction.BLOCK, RuleSeverity.CRITICAL, "违反数据安全政策 §B.1"));

        rules.add(new ConstitutionRule("max-output-length", "输出长度限制", List.of(RuleScope.OUTPUT),
                List.of(new RuleCheck("length", "50000")),
                RuleAction.WARN, RuleSeverity.LOW, "输出长度超限"));

        rules.add(new ConstitutionRule("no-malicious-urls", "禁止异常URL", List.of(RuleScope.INPUT, RuleScope.OUTPUT),
                List.of(new RuleCheck("url", "")),
                RuleAction.BLOCK, RuleSeverity.MEDIUM, "URL 数量异常"));

        rules.add(new ConstitutionRule("no-hate-speech", "禁止仇恨言论", List.of(RuleScope.INPUT, RuleScope.OUTPUT),
                List.of(new RuleCheck("llm", "内容是否包含仇恨言论或歧视性内容")),
                RuleAction.BLOCK, RuleSeverity.CRITICAL, "违反内容安全政策 §C.1"));

        rules.add(new ConstitutionRule("no-self-harm", "禁止自残引导", List.of(RuleScope.INPUT, RuleScope.OUTPUT),
                List.of(new RuleCheck("llm", "内容是否鼓励或提供自残/自杀的方法")),
                RuleAction.BLOCK, RuleSeverity.CRITICAL, "违反安全政策 §C.3"));

        rules.add(new ConstitutionRule("no-weapons-instruction", "禁止武器制造指导", List.of(RuleScope.OUTPUT),
                List.of(new RuleCheck("contains", "制造武器"), new RuleCheck("contains", "制作爆炸物"),
                        new RuleCheck("contains", "合成毒品")),
                RuleAction.BLOCK, RuleSeverity.CRITICAL, "违反合规政策 §A.5"));

        return new Constitution("1.0", "CodePilot 默认治理章程", rules);
    }

    private void populateRejectionContext(RuntimeContext rc, List<Violation> violations) {
        String reasons = violations.stream()
                .map(v -> v.ruleId() + ": " + v.reason())
                .collect(Collectors.joining("; "));

        rc.put("constitution.blocked", true);
        rc.put("constitution.violations", violations);
        rc.put("constitution.summary", "章程违规: " + reasons);
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public int ruleCount() {
        return constitution != null && constitution.rules() != null ? constitution.rules().size() : 0;
    }

    public Map<String, Long> getViolationStats() {
        return new LinkedHashMap<>(violationCounters);
    }

    // ========== 数据记录 ==========

    public enum RuleScope { INPUT, OUTPUT, BOTH }
    public enum RuleAction { BLOCK, WARN, LOG_ONLY }
    public enum RuleSeverity { CRITICAL, HIGH, MEDIUM, LOW }

    public record Constitution(String version, String description, List<ConstitutionRule> rules) {}
    public record ConstitutionRule(String id, String description, List<RuleScope> scope,
                                    List<RuleCheck> checks, RuleAction action, RuleSeverity severity, String reason) {}
    public record RuleCheck(String type, String value) {}
    public record Violation(String ruleId, String description, RuleAction action,
                             RuleSeverity severity, String reason, RuleScope scope, String detail) {}
    private record CheckResult(boolean matched, String detail) {}
}
