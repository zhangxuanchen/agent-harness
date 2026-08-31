package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于探针的信息保真度评测器（配套仓库教学实现，非 AgentScope 内置）。
 * <p>对应书中 KP 6.7.2 信息保真度评测。
 *
 * <p>核心思想：相比 ROUGE 等字面重叠指标，"探针式评测"更能捕捉关键事实的丢失——
 * 例如压缩后丢掉某个文件路径，ROUGE 可能仍打高分，但 Agent 已无法回答"提到了哪个文件"。
 *
 * <p>评测流程：
 * <ol>
 *   <li>{@link #constructProbes(String)} —— 用正则从原始上下文提取关键信息点，构造探针</li>
 *   <li>{@link #askAgent(String, String)} —— 以压缩后上下文为背景，通过 ChatClient 向 Agent 提问探针问题</li>
 *   <li>计算 FidelityScore = 正确回答的探针数 / 总探针数</li>
 * </ol>
 *
 * <p>页面参考：Ch6 §6.7.2 信息保真度评测
 */
@Component
public class ProbeBasedEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ProbeBasedEvaluator.class);

    /** 文件路径提取正则：匹配带扩展名的路径或文件名 */
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "[\\w/.\\-]+\\.[A-Za-z]{1,6}");

    /** 错误信息提取正则：匹配含 error/exception/错误/异常/失败 的整行 */
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?m)^.*(?:error|exception|错误|异常|失败|failed).*$",
            Pattern.CASE_INSENSITIVE);

    /** 决策内容提取正则：匹配含"决定/选择/采用/方案/架构"等决策关键词的整行 */
    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "(?m)^.*(?:决定|选择|采用|方案|架构|技术选型|decided|chose|adopt|architecture).*$",
            Pattern.CASE_INSENSITIVE);

    /** 推理过程提取正则：匹配含"因为/所以/由于/因此"等推理关键词的整行 */
    private static final Pattern REASONING_PATTERN = Pattern.compile(
            "(?m)^.*(?:因为|所以|由于|因此|原因|reason|because|therefore|hence).*$",
            Pattern.CASE_INSENSITIVE);

    /** 常见代码 / 配置文件扩展名，用于过滤误报的"文件路径" */
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "scala", "groovy", "py", "rb", "js", "ts", "jsx", "tsx",
            "go", "rs", "c", "cpp", "cc", "h", "hpp", "cs", "swift", "php",
            "yml", "yaml", "json", "xml", "toml", "properties", "gradle", "md", "sql"
    );

    private final ChatClient chatClient;

    /**
     * 构造器注入 ChatClient（Spring AI）。
     *
     * @param chatClient 用于向 Agent 提问探针问题的 ChatClient
     */
    public ProbeBasedEvaluator(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 探针类型。
     */
    public enum ProbeType {
        /** 事实型：文件路径、错误信息等可核验的硬事实 */
        FACTUAL,
        /** 决策型：架构选择、方案取舍等决策内容 */
        DECISION,
        /** 推理型：讨论过程、因果推导等推理内容 */
        REASONING
    }

    /**
     * 探针：针对上下文中某个关键信息点的提问。
     *
     * @param question       探针问题
     * @param expectedAnswer 预期答案（来自原始上下文）
     * @param type           探针类型
     */
    public record Probe(
            String question,
            String expectedAnswer,
            ProbeType type
    ) {}

    /**
     * 信息保真度评测结果。
     *
     * @param overallFidelity 总体保真度 = 正确探针数 / 总探针数
     * @param fidelityByType  按类型统计的保真度
     * @param totalProbes     探针总数
     * @param correctProbes   正确回答的探针数
     */
    public record FidelityResult(
            double overallFidelity,
            Map<ProbeType, Double> fidelityByType,
            int totalProbes,
            int correctProbes
    ) {}

    /**
     * Demo 用保真度评测：不依赖 ChatClient，直接比较压缩后上下文是否包含探针预期答案。
     * 用于离线演示和单元测试，生产环境请使用 evaluate() 方法（通过 ChatClient 实际提问验证）。
     *
     * @param originalContext   原始上下文
     * @param compressedContext 压缩后上下文
     * @return 保真度评测结果
     */
    public FidelityResult evaluateForDemo(String originalContext, String compressedContext) {
        List<Probe> probes = constructProbes(originalContext);
        int correctCount = 0;
        Map<ProbeType, Integer> correctByType = new EnumMap<>(ProbeType.class);
        Map<ProbeType, Integer> totalByType = new EnumMap<>(ProbeType.class);

        for (Probe probe : probes) {
            totalByType.merge(probe.type(), 1, Integer::sum);
            // Demo 简化判断：压缩后上下文中是否直接包含预期答案
            boolean isCorrect = judgeAnswer(compressedContext, probe.expectedAnswer());
            if (isCorrect) {
                correctCount++;
                correctByType.merge(probe.type(), 1, Integer::sum);
            }
        }

        double overallFidelity = probes.isEmpty() ? 1.0 : (double) correctCount / probes.size();
        Map<ProbeType, Double> fidelityByType = new EnumMap<>(ProbeType.class);
        for (ProbeType type : ProbeType.values()) {
            int total = totalByType.getOrDefault(type, 0);
            int correct = correctByType.getOrDefault(type, 0);
            if (total > 0) {
                fidelityByType.put(type, (double) correct / total);
            }
        }
        return new FidelityResult(overallFidelity, fidelityByType, probes.size(), correctCount);
    }

    /**
     * 评测压缩前后的信息保真度。
     *
     * @param originalContext   原始（压缩前）上下文
     * @param compressedContext 压缩后上下文
     * @return 保真度评测结果
     */
    public FidelityResult evaluate(String originalContext, String compressedContext) {
        // 步骤一：从原始上下文构造探针
        List<Probe> probes = constructProbes(originalContext);
        log.info("[ProbeEval] 构造探针: total={}", probes.size());

        if (probes.isEmpty()) {
            // 无可提取的关键信息点，视为无信息丢失
            log.info("[ProbeEval] 未构造出探针，保真度=1.0");
            return new FidelityResult(1.0, Map.of(), 0, 0);
        }

        // 按类型统计正确数 / 总数
        Map<ProbeType, Integer> correctByType = new EnumMap<>(ProbeType.class);
        Map<ProbeType, Integer> totalByType = new EnumMap<>(ProbeType.class);
        for (ProbeType t : ProbeType.values()) {
            correctByType.put(t, 0);
            totalByType.put(t, 0);
        }

        int correctProbes = 0;
        // 步骤二 + 三：逐个探针向 Agent 提问并判断正误
        for (Probe probe : probes) {
            totalByType.merge(probe.type(), 1, Integer::sum);
            String answer = askAgent(compressedContext, probe.question());
            if (judgeAnswer(answer, probe.expectedAnswer())) {
                correctProbes++;
                correctByType.merge(probe.type(), 1, Integer::sum);
            }
        }

        // 步骤三：计算总体保真度与按类型保真度
        double overallFidelity = (double) correctProbes / probes.size();
        Map<ProbeType, Double> fidelityByType = new EnumMap<>(ProbeType.class);
        for (ProbeType t : ProbeType.values()) {
            int total = totalByType.getOrDefault(t, 0);
            int correct = correctByType.getOrDefault(t, 0);
            fidelityByType.put(t, total == 0 ? 0.0 : (double) correct / total);
        }

        log.info("[ProbeEval] 保真度评测完成: total={}, correct={}, fidelity={}",
                probes.size(), correctProbes,
                String.format(Locale.ROOT, "%.4f", overallFidelity));

        return new FidelityResult(overallFidelity, fidelityByType, probes.size(), correctProbes);
    }

    /**
     * 步骤一：用正则从原始上下文提取关键信息点（文件路径、错误信息、决策内容、推理过程），构造探针。
     *
     * @param context 原始上下文
     * @return 探针列表
     */
    public List<Probe> constructProbes(String context) {
        List<Probe> probes = new ArrayList<>();
        if (context == null || context.isBlank()) {
            return probes;
        }

        // 1. 文件路径 → FACTUAL 探针
        Set<String> paths = new LinkedHashSet<>();
        Matcher pathMatcher = FILE_PATH_PATTERN.matcher(context);
        while (pathMatcher.find()) {
            String candidate = pathMatcher.group();
            if (isLikelyFilePath(candidate)) {
                paths.add(candidate);
            }
        }
        for (String path : paths) {
            probes.add(new Probe(
                    "根据上下文，是否提到了文件路径 '" + path + "'？如果是，请复述该路径。",
                    path,
                    ProbeType.FACTUAL));
        }

        // 2. 错误信息 → FACTUAL 探针
        Matcher errorMatcher = ERROR_PATTERN.matcher(context);
        while (errorMatcher.find()) {
            String line = errorMatcher.group().trim();
            if (!line.isEmpty()) {
                probes.add(new Probe(
                        "根据上下文，提到了什么错误信息？请复述。",
                        line,
                        ProbeType.FACTUAL));
            }
        }

        // 3. 决策内容 → DECISION 探针
        Matcher decisionMatcher = DECISION_PATTERN.matcher(context);
        while (decisionMatcher.find()) {
            String line = decisionMatcher.group().trim();
            if (!line.isEmpty()) {
                probes.add(new Probe(
                        "根据上下文，做了什么技术决策？请复述决策内容。",
                        line,
                        ProbeType.DECISION));
            }
        }

        // 4. 推理过程 → REASONING 探针
        Matcher reasoningMatcher = REASONING_PATTERN.matcher(context);
        while (reasoningMatcher.find()) {
            String line = reasoningMatcher.group().trim();
            if (!line.isEmpty()) {
                probes.add(new Probe(
                        "根据上下文，推理依据是什么？请复述推理过程。",
                        line,
                        ProbeType.REASONING));
            }
        }

        return probes;
    }

    /**
     * 步骤二：用压缩后的上下文作为背景，通过 ChatClient 向 Agent 提问探针问题。
     *
     * @param context  压缩后的上下文（作为背景）
     * @param question 探针问题
     * @return Agent 的回答；调用失败或 ChatClient 未注入时返回空字符串
     */
    public String askAgent(String context, String question) {
        if (chatClient == null) {
            log.warn("[ProbeEval] ChatClient 未注入，无法提问，返回空回答");
            return "";
        }

        String prompt = """
                你是信息保真度评测助手。下面是经过压缩的上下文背景，请仅基于该背景回答问题。
                如果背景中没有相关信息，请回答"未提及"。

                压缩后的上下文：
                ---
                %s
                ---

                问题：%s
                """.formatted(context == null ? "" : context, question);

        try {
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[ProbeEval] 调用 ChatClient 失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 判断回答是否包含预期答案。
     * <p>简单实现用 {@code contains}；生产环境可用 LLM 做语义判断。
     *
     * @param answer   Agent 的回答
     * @param expected 预期答案
     * @return 回答是否包含预期答案
     */
    public boolean judgeAnswer(String answer, String expected) {
        if (answer == null || expected == null) {
            return false;
        }
        return answer.contains(expected);
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
