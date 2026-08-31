package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 关键信息锚定过滤器。
 * 配套仓库教学实现，非 AgentScope 内置。
 *
 * 对应书中 KP 6.4.1 "写入侧治理：关键信息锚定"的筛选规则。
 * 每条工具返回做完之后，用两条规则判断其中哪些信息需要锚定：
 * 规则一（失效后果）：忽略后导致不可逆错误的约束或危险操作信号
 * 规则二（依赖程度）：后续一定会被引用的具体值或跨多步生效的规范
 */
@Component
public class AnchorFilter {

    private static final Logger log = LoggerFactory.getLogger(AnchorFilter.class);

    /**
     * 规则一：失效后果关键词。
     * 出现在工具返回中意味着"这条信息忽略了会出事"。
     * 包含否定约束词（不要、禁止）、破坏性操作词（删除、drop）、环境限定词（生产、prod）。
     */
    private static final List<String> CONSTRAINT_WORDS = List.of(
            "不要", "禁止", "严禁", "不得", "不能",
            "delete", "drop", "truncate", "remove",
            "生产", "prod", "线上", "正式环境"
    );

    /**
     * 规则二（具体值）：正则匹配文件路径、ID、URL、token/password 等。
     * 这类信息后续步骤几乎一定会被引用。
     */
    private static final Pattern SPECIFIC_VALUE_PATTERN = Pattern.compile(
            "(?i)"
            + "(/[\\w.-]+/[\\w./-]+)"                     // 文件路径
            + "|(https?://[^\\s]+)"                        // URL
            + "|([a-zA-Z_][a-zA-Z0-9_]*[Ii][Dd])"          // ID 后缀
            + "|(api[_-]?key|token|password|secret)"       // 敏感凭据关键词
            + "|([0-9a-fA-F]{32,})"                        // 长十六进制哈希/token
    );

    /**
     * 规则二（规范信号）：编码风格、命名约定、目录结构等关键词。
     * 这些规范会在后续代码生成步骤中被反复依赖。
     */
    private static final List<String> NORM_SIGNALS = List.of(
            "命名", "编码风格", "代码规范", "目录结构", "包名",
            "驼峰", "蛇形", "kebab", "camelCase", "snake_case",
            "缩进", "格式化", "必须用", "统一用", "规范"
    );

    /**
     * 误报消歧黑名单。
     * 这些词虽然包含 CONSTRAINT_WORDS 的字串，但语境上不是约束而是日常对话。
     */
    private static final List<String> DISAMBIGUATION_BLACKLIST = List.of(
            "不要紧", "不要了", "没问题", "不用担心", "不用了", "不需要了"
    );

    /**
     * 从工具返回内容中筛选需要锚定的关键信息。
     *
     * @param toolResult 工具返回的原始内容
     * @param toolName   工具名（来自 rc.getExtra().getOrDefault("tool.name", "unknown")）
     * @return 需要锚定的条目列表，按风险和依赖程度排序
     */
    public List<AnchorItem> filter(String toolResult, String toolName) {
        List<AnchorItem> result = new ArrayList<>();
        if (toolResult == null || toolResult.isBlank()) {
            return result;
        }

        // 规则一：失效后果严重程度——关键词匹配
        for (String line : toolResult.split("\n")) {
            String trimmed = line.trim();
            // 先过滤误报：整行在消歧黑名单里的跳过
            if (DISAMBIGUATION_BLACKLIST.stream().anyMatch(trimmed::equals)) {
                continue;
            }
            if (CONSTRAINT_WORDS.stream().anyMatch(trimmed::contains)) {
                result.add(new AnchorItem(truncate(trimmed), true, false));
            }
        }

        // 规则二（具体值）：正则匹配
        var matcher = SPECIFIC_VALUE_PATTERN.matcher(toolResult);
        while (matcher.find()) {
            String match = matcher.group();
            if (match.length() > 3) {
                result.add(new AnchorItem(truncate(match), false, true));
            }
        }

        // 规则二（规范信号）：关键词匹配
        for (String line : toolResult.split("\n")) {
            String trimmed = line.trim();
            if (NORM_SIGNALS.stream().anyMatch(trimmed::contains)) {
                result.add(new AnchorItem(truncate(trimmed), false, true));
            }
        }

        log.debug("[AnchorFilter] 工具 {} 返回 {} 字符，锚定 {} 条",
                toolName, toolResult.length(), result.size());
        return result;
    }

    private String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * 锚定条目。
     * @param content         需要锚定的文本片段
     * @param highRisk        是否高风险（规则一命中：忽略后会出事）
     * @param highDependency  是否高依赖（规则二命中：后续步骤一定会引用）
     */
    public record AnchorItem(
            String content,
            boolean highRisk,
            boolean highDependency
    ) {
        /** 判断是否属于最高优先级——既高风险又高依赖 */
        public boolean isCritical() {
            return highRisk && highDependency;
        }
    }
}
