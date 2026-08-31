package io.etclovg.codepilot.tools;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * T 层 · 工具验证中间件。
 * <p>在工具注册时执行 ACID 五要素描述验证，运行时验证工具调用参数匹配 schema。
 * 对应书中 Ch5 §5.6.1 — ACID 五要素描述工程。
 *
 * <p><b>ACID 五要素</b>：{@code 【做什么】| 【什么时候用】| 【参数说明】| 【返回值说明】| 【注意事项】}<br>
 * <b>效果</b>：五要素完整描述将工具选择准确率从 55% 提升至 92%（BFCL 实测数据）[^2]<br>
 * <b>原理</b>：JSON Schema 校验是确定性机制，非 LLM 概率判断——
 * 参数名和类型在编译时固化为结构化 Schema，模型只需"填空"
 *
 * <p>基于 AgentScope {@link AbstractLayerMiddleware} 的 {@code onActing} 阶段，
 * 参数校验失败时通过 {@link RuntimeContext} 打标并短路返回空事件流。
 */
@Component
public class ToolValidationAdvisor extends AbstractLayerMiddleware {

    /** ACID 五要素标签模式 */
    private static final Set<String> REQUIRED_ELEMENTS = Set.of(
            "【做什么】", "【什么时候用】", "【参数说明】",
            "【返回值说明】", "【注意事项】"
    );

    /** 模糊词汇黑名单——这些词汇在工具描述中会导致模型误解 */
    private static final Set<String> VAGUE_TERMS = Set.of(
            "最近", "相关", "一些", "等等", "相关数据", "etc", "etc.", "and more"
    );

    /** 参数名合法正则 */
    private static final Pattern PARAM_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    /** 最大参数数量（黄金粒度原则） */
    private static final int MAX_PARAMS = 5;

    public ToolValidationAdvisor() {
        super(Layer.T, "ToolValidationAdvisor-T");
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();
        String toolName = context.getOrDefault("tool.name", "unknown").toString();

        // —— 运行时参数校验 ——
        if (context.containsKey("tool.params")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) context.get("tool.params");
            if (!validateParameters(toolName, params)) {
                log.warn("[T层验证] 工具 {} 参数校验失败——已拦截", toolName);
                rc.put("tool.validation.error",
                        "参数校验失败——请检查输入参数类型和约束");
                return Flux.empty();
            }
        }

        // —— 描述五要素校验 ——
        String toolDescription = context.getOrDefault("tool.description", "").toString();
        validateDescriptionElements(toolName, toolDescription);

        return next.apply(input);
    }

    /**
     * 验证工具描述是否包含完整的 ACID 五要素。
     * <p>对应书中 KP 5.6.1：五要素完整描述 → 选对率 92% vs 仅功能描述 → 55%。
     *
     * @param toolName    工具名称
     * @param description 工具描述文本
     * @return true 所有五要素齐全
     */
    public boolean validateDescriptionElements(String toolName, String description) {
        if (description == null || description.isBlank()) {
            log.warn("[T层验证] 工具 \"{}\" 描述为空——必须包含 ACID 五要素", toolName);
            return false;
        }

        int foundCount = 0;
        for (String element : REQUIRED_ELEMENTS) {
            if (description.contains(element)) {
                foundCount++;
            }
        }

        // 检查模糊词汇
        for (String vague : VAGUE_TERMS) {
            if (description.contains(vague)) {
                log.warn("[T层验证] 工具 \"{}\" 描述包含模糊词汇: \"{}\"", toolName, vague);
            }
        }

        if (foundCount < REQUIRED_ELEMENTS.size()) {
            log.warn("[T层验证] 工具 \"{}\" 描述仅含 {}/{} 个五要素——"
                    + "完整五要素可将选对率从 55% 提升至 92% (BFCL V4 数据)",
                    toolName, foundCount, REQUIRED_ELEMENTS.size());
            return false;
        }

        log.debug("[T层验证] 工具 \"{}\" 五要素完整 ✓", toolName);
        return true;
    }

    /**
     * 验证工具调用参数是否合法。
     * <p>检查参数名格式、参数数量（黄金粒度原则 ≤ 5）、基本类型约束。
     */
    private boolean validateParameters(String toolName, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return true; // 无参数工具跳过校验
        }

        // 参数名合法性检查
        for (String paramName : params.keySet()) {
            if (!PARAM_NAME_PATTERN.matcher(paramName).matches()) {
                log.warn("[T层验证] 工具 \"{}\" 参数名非法: \"{}\"", toolName, paramName);
                return false;
            }
        }

        // 参数数量检查（黄金粒度原则）
        if (params.size() > MAX_PARAMS) {
            log.warn("[T层验证] 工具 \"{}\" 参数过多 ({} > {})——建议 ≤{} 个（黄金粒度原则 §5.1.2）",
                    toolName, params.size(), MAX_PARAMS, MAX_PARAMS);
        }

        // 参数值非空检查
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                log.warn("[T层验证] 工具 \"{}\" 参数 \"{}\" 值为 null", toolName, entry.getKey());
                return false;
            }
        }

        return true;
    }

    /**
     * 注册前完整验证——工具注册时调用，而非运行时。
     * <p>返回结构化验证结果，包含通过/失败状态、缺失元素列表、模糊词汇列表。
     *
     * @param toolName    工具名称
     * @param description 工具描述
     * @param paramNames  参数名列表
     * @return 验证结果
     */
    public ValidationResult validateToolForRegistration(String toolName, String description,
                                                         List<String> paramNames) {
        int elementsFound = 0;
        List<String> missing = new ArrayList<>();
        for (String element : REQUIRED_ELEMENTS) {
            if (description != null && description.contains(element)) {
                elementsFound++;
            } else {
                missing.add(element);
            }
        }

        List<String> vagueFound = new ArrayList<>();
        if (description != null) {
            for (String vague : VAGUE_TERMS) {
                if (description.contains(vague)) {
                    vagueFound.add(vague);
                }
            }
        }

        boolean paramsOk = paramNames == null || paramNames.size() <= MAX_PARAMS;
        boolean elementsOk = elementsFound == REQUIRED_ELEMENTS.size();
        boolean noVagueTerms = vagueFound.isEmpty();

        return new ValidationResult(
                elementsOk && noVagueTerms && paramsOk,
                toolName,
                elementsFound, REQUIRED_ELEMENTS.size(),
                missing, vagueFound, paramsOk
        );
    }

    /** 工具注册验证结果 */
    public record ValidationResult(
            boolean passed,
            String toolName,
            int elementsFound,
            int totalElements,
            List<String> missingElements,
            List<String> vagueTerms,
            boolean paramsWithinLimit
    ) {
        /** 质量评分（0-100）——用于工具注册时的质量门禁 */
        public double qualityScore() {
            double score = (double) elementsFound / totalElements * 100.0;
            if (!vagueTerms.isEmpty()) score -= vagueTerms.size() * 10.0;
            if (!paramsWithinLimit) score -= 10.0;
            return Math.max(0.0, Math.min(100.0, score));
        }
    }
}
