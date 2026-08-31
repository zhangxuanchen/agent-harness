package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * C 层 · KV-cache 友好的系统提示构建器。
 *
 * <p>实现 Ch6 §6.3.1 定义的 KV-cache 稳定前缀设计——将系统提示分为：
 * <ul>
 *   <li><b>稳定前缀（Stable Prefix）</b>：所有请求完全相同的部分，可复用 KV-cache</li>
 *   <li><b>动态后缀（Dynamic Suffix）</b>：每次请求可能不同的部分，放在 user message 中</li>
 * </ul>
 *
 * <h3>稳定前缀组成</h3>
 * <pre>
 * ┌────────────────────────────────────────┐
 * │         KV-Cache 稳定前缀               │
 * ├────────────────────────────────────────┤
 * │ 1. 安全约束（最高优先级）                │
 * │ 2. 工具定义（function signatures）       │
 * │ 3. 行为规则（behavior rules）            │
 * │ 4. 其他稳定片段（按优先级排序）           │
 * └────────────────────────────────────────┘
 * </pre>
 *
 * <h3>动态后缀组成</h3>
 * <pre>
 * ┌────────────────────────────────────────┐
 * │         User Message 动态后缀           │
 * ├────────────────────────────────────────┤
 * │ 1. 时间戳（Current time）               │
 * │ 2. SessionId                           │
 * │ 3. 用户偏好信息                          │
 * │ 4. 任务特定上下文                        │
 * └────────────────────────────────────────┘
 * </pre>
 *
 * <h3>KV-cache 复用原理</h3>
 * <p>LLM 推理时，已计算过的 KV-cache 可以复用：
 * <ul>
 *   <li>如果系统提示完全相同 → 复用整个 KV-cache（延迟从 2s 降到 200ms）</li>
 *   <li>如果系统提示有 1 个字符不同 → 整个 KV-cache 失效</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CacheAwareSystemPromptBuilder builder = new CacheAwareSystemPromptBuilder();
 *
 * // 添加稳定片段
 * builder.addSection(SystemPromptSection.tools(toolDefinitions));
 * builder.addSection(SystemPromptSection.behaviorRules(behaviorRules));
 *
 * // 构建系统提示
 * SystemPrompt prompt = builder.build();
 *
 * // 获取稳定前缀（用于 KV-cache）
 * String stablePrefix = prompt.stablePrefix();
 *
 * // 构建动态后缀（放入 user message）
 * String dynamicSuffix = builder.buildDynamicSuffix(sessionId, currentTime);
 * }</pre>
 *
 * <p><b>页面参考</b>：Ch6 §6.3.1 KV-cache 稳定前缀 / §6.3.2 动态内容隔离
 *
 * @see SystemPromptSection
 * @see SystemPrompt
 */
@Component
public class CacheAwareSystemPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(CacheAwareSystemPromptBuilder.class);

    // ==================== 核心数据结构 ====================

    /** 系统提示片段列表（按优先级排序） */
    private final List<SystemPromptSection> sections = new ArrayList<>();

    /** 缓存的稳定前缀（在第一次 build 后缓存） */
    private final AtomicReference<String> cachedStablePrefix = new AtomicReference<>("");

    /** 缓存的稳定前缀 token 数量 */
    private volatile int cachedStablePrefixTokens = 0;

    /** 缓存是否失效（添加/删除片段后失效） */
    private volatile boolean cacheInvalid = true;

    /** 严格模式：遇到 STABLE 片段包含动态内容时抛出异常 */
    private boolean strictMode = true;

    /** 是否启用动态内容检测 */
    private boolean enableDynamicDetection = true;

    // ==================== 构建方法 ====================

    /**
     * 添加一个系统提示片段。
     *
     * <p>片段会按照优先级自动排序（priority 越小越靠前）。
     *
     * @param section 系统提示片段
     * @return this（支持链式调用）
     */
    public CacheAwareSystemPromptBuilder addSection(SystemPromptSection section) {
        if (section == null) {
            log.warn("[C层·KV-Cache] 尝试添加 null 片段，已忽略");
            return this;
        }

        // 验证片段
        if (strictMode && section.isStable() && enableDynamicDetection) {
            SystemPromptSection.ValidationResult validation = section.validate();
            if (!validation.isValid()) {
                throw new IllegalArgumentException(
                        "稳定片段验证失败: " + validation.errorMessage() +
                        "。提示：稳定片段不能包含时间戳、随机数、sessionId 等动态内容。");
            }
        }

        sections.add(section);
        cacheInvalid = true;

        log.debug("[C层·KV-Cache] 添加片段: name={}, type={}, priority={}, tokens={}",
                section.name(), section.type(), section.priority(), section.estimateTokens());

        return this;
    }

    /**
     * 添加多个系统提示片段。
     *
     * @param sections 系统提示片段列表
     * @return this
     */
    public CacheAwareSystemPromptBuilder addSections(List<SystemPromptSection> sections) {
        if (sections != null) {
            sections.forEach(this::addSection);
        }
        return this;
    }

    /**
     * 移除指定名称的片段。
     *
     * @param name 片段名称
     * @return this
     */
    public CacheAwareSystemPromptBuilder removeSection(String name) {
        boolean removed = sections.removeIf(s -> s.name().equals(name));
        if (removed) {
            cacheInvalid = true;
            log.debug("[C层·KV-Cache] 移除片段: name={}", name);
        }
        return this;
    }

    /**
     * 清空所有片段。
     *
     * @return this
     */
    public CacheAwareSystemPromptBuilder clear() {
        sections.clear();
        cacheInvalid = true;
        log.debug("[C层·KV-Cache] 清空所有片段");
        return this;
    }

    /**
     * 构建系统提示。
     *
     * <p>构建过程：
     * <ol>
     *   <li>按优先级排序所有片段</li>
     *   <li>分离稳定片段和动态片段</li>
     *   <li>验证稳定片段不含动态内容</li>
     *   <li>生成稳定前缀并缓存</li>
     *   <li>生成动态后缀模板</li>
     * </ol>
     *
     * @return 系统提示对象
     */
    public SystemPrompt build() {
        // 1. 按优先级排序
        List<SystemPromptSection> sorted = sections.stream()
                .sorted(Comparator.comparingInt(SystemPromptSection::priority))
                .collect(Collectors.toList());

        // 2. 分离稳定和动态片段
        List<SystemPromptSection> stableSections = sorted.stream()
                .filter(SystemPromptSection::isStable)
                .collect(Collectors.toList());

        List<SystemPromptSection> dynamicSections = sorted.stream()
                .filter(SystemPromptSection::isDynamic)
                .collect(Collectors.toList());

        List<SystemPromptSection> semiStableSections = sorted.stream()
                .filter(s -> s.type() == SystemPromptSection.SectionType.SEMI_STABLE)
                .collect(Collectors.toList());

        // 3. 验证稳定片段
        if (enableDynamicDetection) {
            for (SystemPromptSection section : stableSections) {
                if (section.containsDynamicContent()) {
                    String error = String.format(
                            "稳定片段 '%s' 包含动态内容！这将导致 KV-cache 完全失效。" +
                            "请将动态内容（时间戳、sessionId等）移到动态后缀中。",
                            section.name());
                    if (strictMode) {
                        throw new IllegalStateException(error);
                    } else {
                        log.error("[C层·KV-Cache] {}", error);
                    }
                }
            }
        }

        // 4. 生成稳定前缀
        String stablePrefix = buildStablePrefix(stableSections, semiStableSections);

        // 5. 缓存稳定前缀
        if (cacheInvalid || cachedStablePrefix.get().isEmpty()) {
            cachedStablePrefix.set(stablePrefix);
            cachedStablePrefixTokens = ContextBudgetAdvisor.defaultEstimator().estimate(stablePrefix);
            cacheInvalid = false;
        }

        log.info("[C层·KV-Cache] 系统提示构建完成: 稳定片段={}, 动态片段={}, 半稳定片段={}, " +
                "稳定前缀 tokens={}",
                stableSections.size(), dynamicSections.size(), semiStableSections.size(),
                cachedStablePrefixTokens);

        return new SystemPrompt(
                cachedStablePrefix.get(),
                cachedStablePrefixTokens,
                stableSections,
                dynamicSections,
                semiStableSections
        );
    }

    /**
     * 构建稳定前缀。
     */
    private String buildStablePrefix(List<SystemPromptSection> stableSections,
                                     List<SystemPromptSection> semiStableSections) {
        StringBuilder sb = new StringBuilder();

        // 稳定片段
        if (!stableSections.isEmpty()) {
            sb.append("=== 系统提示（KV-cache 稳定前缀） ===\n\n");
            for (SystemPromptSection section : stableSections) {
                sb.append("--- ").append(section.name()).append(" ---\n");
                sb.append(section.content()).append("\n\n");
            }
        }

        // 半稳定片段（会话级常量）
        if (!semiStableSections.isEmpty()) {
            sb.append("=== 会话常量 ===\n\n");
            for (SystemPromptSection section : semiStableSections) {
                sb.append("--- ").append(section.name()).append(" ---\n");
                sb.append(section.content()).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ==================== 动态后缀构建 ====================

    /**
     * 构建动态后缀（放入 user message 中）。
     *
     * <p>动态后缀包含：
     * <ul>
     *   <li>时间戳（当前时间）</li>
     *   <li>SessionId</li>
     *   <li>用户偏好信息</li>
     *   <li>任务特定上下文</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @param currentTime 当前时间
     * @return 动态后缀字符串
     */
    public String buildDynamicSuffix(String sessionId, Instant currentTime) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== 动态上下文 ===\n\n");
        sb.append(String.format("[Session ID: %s]%n", sessionId));
        sb.append(String.format("[Current Time: %s]%n%n", currentTime.toString()));

        // 动态片段
        List<SystemPromptSection> dynamicSections = sections.stream()
                .filter(SystemPromptSection::isDynamic)
                .sorted(Comparator.comparingInt(SystemPromptSection::priority))
                .collect(Collectors.toList());

        if (!dynamicSections.isEmpty()) {
            sb.append("=== 任务上下文 ===\n\n");
            for (SystemPromptSection section : dynamicSections) {
                sb.append("--- ").append(section.name()).append(" ---\n");
                sb.append(section.content()).append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * 构建动态后缀（使用当前时间）。
     *
     * @param sessionId 会话 ID
     * @return 动态后缀字符串
     */
    public String buildDynamicSuffix(String sessionId) {
        return buildDynamicSuffix(sessionId, Instant.now());
    }

    /**
     * 构建完整的用户消息（动态后缀 + 用户输入）。
     *
     * @param userMessage 用户消息
     * @param sessionId 会话 ID
     * @param currentTime 当前时间
     * @return 完整的用户消息
     */
    public String buildUserMessage(String userMessage, String sessionId, Instant currentTime) {
        StringBuilder sb = new StringBuilder();

        // 动态后缀在前
        sb.append(buildDynamicSuffix(sessionId, currentTime));

        // 用户消息在后
        sb.append("=== 用户消息 ===\n\n");
        sb.append(userMessage);

        return sb.toString();
    }

    // ==================== 查询方法 ====================

    /**
     * 获取缓存的稳定前缀。
     *
     * @return 稳定前缀字符串
     */
    public String getCachedStablePrefix() {
        if (cacheInvalid) {
            build();
        }
        return cachedStablePrefix.get();
    }

    /**
     * 获取缓存的稳定前缀 token 数量。
     *
     * @return token 数量
     */
    public int getCachedStablePrefixTokens() {
        if (cacheInvalid) {
            build();
        }
        return cachedStablePrefixTokens;
    }

    /**
     * 获取所有片段。
     *
     * @return 片段列表
     */
    public List<SystemPromptSection> getSections() {
        return new ArrayList<>(sections);
    }

    /**
     * 获取指定名称的片段。
     *
     * @param name 片段名称
     * @return 片段（不存在则返回 null）
     */
    public SystemPromptSection getSection(String name) {
        return sections.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 验证当前构建器的配置是否合法。
     *
     * @return 验证结果
     */
    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();

        for (SystemPromptSection section : sections) {
            if (section.isStable() && section.containsDynamicContent()) {
                errors.add(String.format("稳定片段 '%s' 包含动态内容", section.name()));
            }
        }

        if (!errors.isEmpty()) {
            return ValidationResult.invalid(errors);
        }

        return ValidationResult.valid();
    }

    /**
     * 估算当前系统提示的总 token 数量。
     *
     * @return 估算的 token 数量
     */
    public int estimateTotalTokens() {
        return sections.stream()
                .mapToInt(SystemPromptSection::estimateTokens)
                .sum();
    }

    // ==================== 配置方法 ====================

    public void setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
    }

    public void setEnableDynamicDetection(boolean enableDynamicDetection) {
        this.enableDynamicDetection = enableDynamicDetection;
    }

    // ==================== 内部类型 ====================

    /**
     * 系统提示对象（构建结果）。
     */
    public record SystemPrompt(
            /** 稳定前缀 */
            String stablePrefix,

            /** 稳定前缀 token 数量 */
            int stablePrefixTokens,

            /** 稳定片段列表 */
            List<SystemPromptSection> stableSections,

            /** 动态片段列表 */
            List<SystemPromptSection> dynamicSections,

            /** 半稳定片段列表 */
            List<SystemPromptSection> semiStableSections
    ) {
        /**
         * 获取完整的系统提示（稳定前缀）。
         */
        public String getFullPrompt() {
            return stablePrefix;
        }

        /**
         * 获取片段总数。
         */
        public int getTotalSections() {
            return stableSections.size() + dynamicSections.size() + semiStableSections.size();
        }
    }

    /**
     * 验证结果。
     */
    public record ValidationResult(boolean isValid, List<String> errors) {
        public static ValidationResult valid() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult invalid(List<String> errors) {
            return new ValidationResult(false, errors);
        }

        public String getErrorMessage() {
            return errors.isEmpty() ? "" : String.join("; ", errors);
        }
    }

    @Override
    public String toString() {
        return String.format("CacheAwareSystemPromptBuilder{sections=%d, cachedTokens=%d, cacheInvalid=%s}",
                sections.size(), cachedStablePrefixTokens, cacheInvalid);
    }
}