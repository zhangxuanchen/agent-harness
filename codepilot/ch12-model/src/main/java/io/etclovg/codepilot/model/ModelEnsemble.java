package io.etclovg.codepilot.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 模型集成：基于置信度加权投票的多模型混合推理。
 *
 * <p>对应书中 Ch12 §12.2 KP 12.2.1：三维决策框架（微调 > 路由 > Harness）的
 * <b>第四种中间方案</b>——多模型集成。定位在「路由（低成本但质量中等）」
 * 与「微调（高成本但质量显著提升）」之间：预算 ~ 路由的 2~3 倍、
 * 质量提升 ~ 微调收益的 50~70%、上线周期 < 1 周（vs 微调 2~4 周数据准备）。
 *
 * <p>典型适用场景：法律/医疗/代码重构等对可靠性要求极高但预算充足的场景，
 * 让多个模型一起投票——比微调更容易迭代（换一个模型只需改 modelNames 列表），
 * 也比单模型更稳健（单模型偶发的幻觉被其他模型投票压制）。
 *
 * <p>KP 12.2.1 工程纪律：不要把 ensemble 作为每请求链的默认方案——
 * 集成通常意味着单次任务要调 2~3 个模型，成本与延迟均成倍增加。
 * 正确做法：仅在 V 层 EmbeddedValidationAdvisor 返回
 * {@code validation.correction=true && confidence<0.7} 时触发 ensemble 作为"再验证"。
 */
@Component
public class ModelEnsemble {

    private static final Logger log = LoggerFactory.getLogger(ModelEnsemble.class);

    public record EnsembleResult(
            String finalAnswer,
            Map<String, Double> votes,
            double confidence,
            Map<String, Double> modelWeightsSnapshot) {}

    /** 默认集成模型列表（KP 12.2.1 典型三档组合：1 强 + 1 中 + 1 轻） */
    private List<String> modelNames = new ArrayList<>(List.of("sonnet-4.6", "gpt-5.4-mini", "haiku-4.5"));

    /** 模型权重（与 §12.2 capability/qualityScore 正相关；默认 Sonnet 权重最高） */
    private final Map<String, Double> modelWeights = new LinkedHashMap<>();

    public ModelEnsemble() {
        modelWeights.put("sonnet-4.6", 0.40);
        modelWeights.put("gpt-5.4-mini", 0.35);
        modelWeights.put("haiku-4.5", 0.25);
    }

    /**
     * 加权投票集成（KP 12.2.1 推荐方案：能力 × 历史准确率加权）。
     *
     * @param modelOutputs 每个 modelId → 该模型跑 1~N 次的输出列表（含温度扰动）
     */
    public EnsembleResult weightedVote(Map<String, List<String>> modelOutputs) {
        Map<String, Double> scoreBoard = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : modelOutputs.entrySet()) {
            String modelName = entry.getKey();
            List<String> outputs = entry.getValue();
            double weight = modelWeights.getOrDefault(modelName, 0.2);

            for (String output : outputs) {
                String canonical = canonicalize(output);
                scoreBoard.merge(canonical, weight, Double::sum);
            }
        }

        Map.Entry<String, Double> winner = scoreBoard.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(Map.entry("UNKNOWN", 0.0));
        double totalWeight = scoreBoard.values().stream().mapToDouble(Double::doubleValue).sum();
        double confidence = totalWeight > 0 ? winner.getValue() / totalWeight : 0.0;

        log.info("[ModelEnsemble KP 12.2.1] 加权投票：候选={}，获胜置信度={:.2f}",
                scoreBoard.size(), confidence);
        return new EnsembleResult(winner.getKey(), scoreBoard, confidence, Map.copyOf(modelWeights));
    }

    /**
     * 多数投票（简单投票，不考虑模型权重——当各模型能力接近时使用）。
     */
    public EnsembleResult majorityVote(Map<String, List<String>> modelOutputs) {
        Map<String, Integer> voteCount = new LinkedHashMap<>();

        for (List<String> outputs : modelOutputs.values()) {
            for (String output : outputs) {
                voteCount.merge(canonicalize(output), 1, Integer::sum);
            }
        }

        Map.Entry<String, Integer> winner = voteCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(Map.entry("UNKNOWN", 0));
        int totalVotes = voteCount.values().stream().mapToInt(Integer::intValue).sum();
        double confidence = totalVotes > 0 ? (double) winner.getValue() / totalVotes : 0.0;

        Map<String, Double> scores = new LinkedHashMap<>();
        voteCount.forEach((k, v) -> scores.put(k, (double) v));
        return new EnsembleResult(winner.getKey(), scores, confidence, Map.copyOf(modelWeights));
    }

    /**
     * 自适应权重：根据 V 层历史表现动态调整模型权重（KP 12.2.1 第二推荐：随评估周期迭代）。
     *
     * @param modelPerformanceScores modelId → V 层 qualityScore 均值（0~100）
     */
    public void adjustWeights(Map<String, Double> modelPerformanceScores) {
        if (modelPerformanceScores.isEmpty()) return;
        double totalScore = modelPerformanceScores.values().stream().mapToDouble(Double::doubleValue).sum();
        modelPerformanceScores.forEach((model, score) ->
            modelWeights.put(model, totalScore > 0 ? score / totalScore : 1.0 / modelPerformanceScores.size())
        );
        log.info("[ModelEnsemble] 权重已根据 V 层分数调整：{}", modelWeights);
    }

    // ═══════ setter 模式（业务侧定制模型列表，与 Ch11 约定一致） ═══════

    public void setModelNames(List<String> names) { this.modelNames = new ArrayList<>(names); }
    public void setInitialWeights(Map<String, Double> weights) {
        modelWeights.clear();
        modelWeights.putAll(weights);
    }

    public List<String> getModelNames() { return Collections.unmodifiableList(modelNames); }
    public Map<String, Double> getModelWeights() { return Collections.unmodifiableMap(modelWeights); }

    // ═══════ 内部工具：对候选输出做规范（去空白 + 去标点末尾 + 小写化首段），避免同内容因格式差异分流 ═══════
    private static String canonicalize(String output) {
        if (output == null) return "";
        String s = output.trim().replaceAll("\\s+", " ");
        // 只取前 200 chars 作为比较键——避免后续发散部分影响投票（同结论同 key）
        return s.substring(0, Math.min(200, s.length())).toLowerCase(Locale.ROOT);
    }
}
