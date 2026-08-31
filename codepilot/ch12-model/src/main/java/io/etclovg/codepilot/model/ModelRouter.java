package io.etclovg.codepilot.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 基础规则路由器（离散 L1 层）。
 *
 * <p>对应书中 Ch12 §12.3 KP 12.3.1：三层路由架构的 L1 规则路由（微秒级，
 * 典型覆盖 60-70% 请求）——按 taskType 关键词映射表直接返回模型 id 字符串，
 * 命中时零 LLM 调用、零延迟。
 *
 * <p>与 {@link LayeredModelRouter} 的职责分工（KP 12.3.1 三层工程纪律）：
 * <ul>
 *   <li>本类（ModelRouter）只做 {@code taskType → modelId 字符串} 的离散映射
 *       = 纯 L1 规则。生产业务可调用 {@link #registerRule(String, String)}
 *       动态扩展规则表，或 setRules(Map) 注入。</li>
 *   <li>LayeredModelRouter 负责 L2 分类（contextTokens + complexity 算术）
 *       + L3 级联升级阈值判定（shouldEscalate / upgradeTier）。</li>
 *   <li>真实 L 层编排顺序：先调 {@code ModelRouter.route(taskType)}，
 *       返回 null 或非命中 → 再调 {@code LayeredModelRouter.route(t,t,c)} 兜底。</li>
 * </ul>
 *
 * <p>模型实例通过 {@link io.agentscope.core.model.ModelRegistry#resolve(String)}
 * 解析，例如 {@code ModelRegistry.resolve("dashscope:qwen-plus")}。
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** L1 规则表：taskType（自动转小写） → modelId 字符串（供 ModelRegistry.resolve）。 */
    private final Map<String, String> rules = new HashMap<>(Map.of(
            "translation",   "haiku-4.5",
            "summarization", "haiku-4.5",
            "faq",           "haiku-4.5",
            "format",        "haiku-4.5",
            "code_review",   "sonnet-4.6",
            "coding",        "sonnet-4.6",
            "analysis",      "sonnet-4.6",
            "legal",         "opus-4.6",
            "reasoning",     "opus-4.6"
    ));

    /** L1 未命中时的默认模型（走 L2 前先给一个合理默认，编排层可覆写）。 */
    private volatile String defaultModel = "sonnet-4.6";

    /**
     * L1 规则路由主入口。
     * @return 命中时返回 modelId 字符串；未命中时返回默认的 defaultModel。
     */
    public String route(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return defaultModel;
        }
        String modelId = rules.getOrDefault(taskType.toLowerCase(), defaultModel);
        log.info("[模型路由 L1 KP12.3.1] taskType={} → modelId={}", taskType, modelId);
        return modelId;
    }

    /** 动态注册单条规则（业务热加载新任务类型时使用）。 */
    public synchronized void registerRule(String taskType, String modelId) {
        rules.put(taskType.toLowerCase(), modelId);
    }

    /** 批量覆写整个规则表（Spring @Bean 初始化 setter）。 */
    public synchronized void setRules(Map<String, String> newRules) {
        rules.clear();
        newRules.forEach((k, v) -> rules.put(k.toLowerCase(), v));
    }

    public void setDefaultModel(String modelId) { this.defaultModel = modelId; }
    public String getDefaultModel() { return defaultModel; }
    public Map<String, String> getRules() { return Map.copyOf(rules); }
}
