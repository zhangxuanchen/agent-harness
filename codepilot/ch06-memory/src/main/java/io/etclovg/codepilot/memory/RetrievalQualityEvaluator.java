package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索质量评测器（配套仓库教学实现，非 AgentScope 内置）。
 * <p>对应书中 KP 6.7.1 检索准确率与召回率评测。
 *
 * <p>本评测器提供两类评测：
 * <ul>
 *   <li>{@link #evaluate(List)} —— 批量检索准确率 / 召回率 / F1 / MRR 评测</li>
 *   <li>{@link #evaluateConsistency(List, Map)} —— 记忆与当前项目状态的一致性评测</li>
 * </ul>
 *
 * <p>指标定义：
 * <ul>
 *   <li>Precision = 相关且被检索到的 / 被检索到的总数</li>
 *   <li>Recall    = 相关且被检索到的 / 相关总数</li>
 *   <li>F1        = 2 * P * R / (P + R)</li>
 *   <li>MRR       = 第一个相关结果排名倒数的平均（Mean Reciprocal Rank）</li>
 * </ul>
 *
 * <p>页面参考：Ch6 §6.7.1 检索准确率与召回率评测
 */
@Component
public class RetrievalQualityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalQualityEvaluator.class);

    /** 文件路径提取正则：匹配带扩展名的路径或文件名 */
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "[\\w/.\\-]+\\.[A-Za-z]{1,6}");

    /** 类名 / 函数名提取正则：多词 CamelCase 标识符（如 MemoryManager、ChatClient），避免误匹配普通单词 */
    private static final Pattern SYMBOL_PATTERN = Pattern.compile(
            "\\b[A-Z][a-z]+(?:[A-Z][a-zA-Z0-9]*)+\\b");

    /** 常见代码 / 配置文件扩展名，用于过滤误报的"文件路径" */
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "scala", "groovy", "py", "rb", "js", "ts", "jsx", "tsx",
            "go", "rs", "c", "cpp", "cc", "h", "hpp", "cs", "swift", "php",
            "yml", "yaml", "json", "xml", "toml", "properties", "gradle", "md", "sql"
    );

    /**
     * 单条检索测试用例。
     *
     * @param query              查询语句
     * @param relevantMemoryIds  标注的相关记忆 ID 集合（ground truth）
     * @param retrievedMemoryIds 实际检索到的记忆 ID 列表（按相关性排序）
     */
    public record RetrievalTestCase(
            String query,
            List<String> relevantMemoryIds,
            List<String> retrievedMemoryIds
    ) {}

    /**
     * 检索质量评测结果。
     *
     * @param precision  准确率
     * @param recall      召回率
     * @param f1          F1 分数
     * @param mrr         平均倒数排名（Mean Reciprocal Rank）
     * @param consistency 记忆-现实一致性（0~1）；未评测时为 -1.0
     */
    public record RetrievalQualityResult(
            double precision,
            double recall,
            double f1,
            double mrr,
            double consistency
    ) {}

    /**
     * 批量评测检索质量：计算 Precision / Recall / F1 / MRR。
     * <p>采用微平均（micro-average）：将所有用例的命中数 / 检索数 / 相关数汇总后再计算。
     *
     * @param testCases 检索测试用例列表
     * @return 评测结果（consistency 字段为 -1.0，需单独调用 {@link #evaluateConsistency}）
     */
    public RetrievalQualityResult evaluate(List<RetrievalTestCase> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            log.info("[RetrievalEval] 无测试用例，返回 0 指标");
            return new RetrievalQualityResult(0.0, 0.0, 0.0, 0.0, -1.0);
        }

        int totalRelevantAndRetrieved = 0; // 相关且被检索到的总数
        int totalRetrieved = 0;             // 被检索到的总数
        int totalRelevant = 0;              // 相关总数
        double reciprocalRankSum = 0.0;    // 各用例倒数排名之和

        for (RetrievalTestCase tc : testCases) {
            Set<String> relevant = new HashSet<>(tc.relevantMemoryIds() == null
                    ? List.of() : tc.relevantMemoryIds());
            List<String> retrieved = tc.retrievedMemoryIds() == null
                    ? List.of() : tc.retrievedMemoryIds();

            // 相关且被检索到：取交集大小
            Set<String> retrievedSet = new HashSet<>(retrieved);
            retrievedSet.retainAll(relevant);
            int hit = retrievedSet.size();

            totalRelevantAndRetrieved += hit;
            totalRetrieved += retrieved.size();
            totalRelevant += relevant.size();

            // MRR：第一个相关结果的排名倒数（1-based）
            double rr = 0.0;
            for (int i = 0; i < retrieved.size(); i++) {
                if (relevant.contains(retrieved.get(i))) {
                    rr = 1.0 / (i + 1);
                    break;
                }
            }
            reciprocalRankSum += rr;
        }

        double precision = totalRetrieved == 0 ? 0.0
                : (double) totalRelevantAndRetrieved / totalRetrieved;
        double recall = totalRelevant == 0 ? 0.0
                : (double) totalRelevantAndRetrieved / totalRelevant;
        double f1 = (precision + recall) == 0.0 ? 0.0
                : 2.0 * precision * recall / (precision + recall);
        double mrr = reciprocalRankSum / testCases.size();

        log.info("[RetrievalEval] 用例数={}, P={}, R={}, F1={}, MRR={}",
                testCases.size(),
                String.format(Locale.ROOT, "%.4f", precision),
                String.format(Locale.ROOT, "%.4f", recall),
                String.format(Locale.ROOT, "%.4f", f1),
                String.format(Locale.ROOT, "%.4f", mrr));

        return new RetrievalQualityResult(precision, recall, f1, mrr, -1.0);
    }

    /**
     * 记忆-现实一致性评测：验证记忆内容是否仍符合当前项目状态。
     * <ul>
     *   <li>记忆提到文件路径 → 验证文件是否存在（projectState 是否包含该路径）</li>
     *   <li>记忆提到函数名 / 类名 → 验证符号是否存在</li>
     * </ul>
     * 一致性 = 仍然有效的条数 / 总条数。
     * <p>说明：projectState 的 key 集合代表"当前仍存在的文件路径 / 符号"。若记忆中提及的
     * 任意一个文件路径或符号不在 projectState 中，则该条记忆判为失效。
     *
     * @param memoryContents 记忆内容列表
     * @param projectState   当前项目状态：key 为文件路径 / 符号名，value 为其当前状态描述
     * @return 一致性分数（0~1）；无记忆时返回 0
     */
    public double evaluateConsistency(List<String> memoryContents, Map<String, String> projectState) {
        if (memoryContents == null || memoryContents.isEmpty()) {
            log.info("[RetrievalEval] 一致性评测：无记忆条目");
            return 0.0;
        }
        Map<String, String> state = projectState == null ? Map.of() : projectState;

        int consistent = 0;
        for (String content : memoryContents) {
            if (isMemoryConsistent(content, state)) {
                consistent++;
            }
        }

        double consistency = (double) consistent / memoryContents.size();
        log.info("[RetrievalEval] 一致性评测: 总数={}, 有效={}, consistency={}",
                memoryContents.size(), consistent,
                String.format(Locale.ROOT, "%.4f", consistency));
        return consistency;
    }

    /**
     * 判断单条记忆是否与项目状态一致：提取其提到的文件路径与符号，
     * 检查是否全部仍存在于 projectState 中。无可验证项时视为一致。
     */
    private boolean isMemoryConsistent(String memoryContent, Map<String, String> projectState) {
        if (memoryContent == null || memoryContent.isBlank()) {
            return true;
        }
        Set<String> artifacts = extractArtifacts(memoryContent);
        if (artifacts.isEmpty()) {
            // 记忆中未提及可验证的文件 / 符号，视为一致
            return true;
        }
        for (String artifact : artifacts) {
            if (!projectState.containsKey(artifact)) {
                return false;
            }
        }
        return true;
    }

    /** 提取记忆内容中提到的文件路径与类名 / 函数名。 */
    private Set<String> extractArtifacts(String content) {
        Set<String> artifacts = new HashSet<>();
        Matcher pathMatcher = FILE_PATH_PATTERN.matcher(content);
        while (pathMatcher.find()) {
            String candidate = pathMatcher.group();
            if (isLikelyFilePath(candidate)) {
                artifacts.add(candidate);
            }
        }
        Matcher symbolMatcher = SYMBOL_PATTERN.matcher(content);
        while (symbolMatcher.find()) {
            artifacts.add(symbolMatcher.group());
        }
        return artifacts;
    }

    /** 判断匹配串是否像一个真实文件路径：含 "/" 或扩展名为已知代码 / 配置文件后缀。 */
    private boolean isLikelyFilePath(String s) {
        if (s == null || !s.contains(".")) {
            return false;
        }
        if (s.contains("/")) {
            return true;
        }
        int lastDot = s.lastIndexOf('.');
        String ext = s.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return CODE_EXTENSIONS.contains(ext);
    }
}
