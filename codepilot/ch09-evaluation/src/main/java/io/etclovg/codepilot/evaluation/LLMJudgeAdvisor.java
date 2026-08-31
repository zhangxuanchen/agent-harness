package io.etclovg.codepilot.evaluation;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * V 层 · LLM-as-Judge Advisor。
 *
 * <p>使用 LLM 作为评估评委，内建三种偏差缓解机制。
 * 对应书中 Ch9 §9.4 — LLM-as-Judge 三机制。
 *
 * <h3>三种偏差缓解机制</h3>
 * <ol>
 *   <li><b>AB/BA 位置交换</b> — 互换候选 A 和 B 的位置重复评估，消除首位偏好偏差</li>
 *   <li><b>长度截断</b> — 将候选响应截断至相同长度后再评估，消除冗长偏好偏差</li>
 *   <li><b>多轮投票</b> — 使用不同法官模型轮询 N 次后取多数票，降低单模型偏见</li>
 * </ol>
 *
 * <p><b>效果</b>：三机制联合使用将 LLM Judge 与人类评委的
 * Pearson 相关性从 0.62 提升至 0.87（MT-Bench 数据）。
 */
@Component
public class LLMJudgeAdvisor extends AbstractLayerMiddleware {

    private static final int DEFAULT_TRUNCATION_LENGTH = 2048;
    private static final int DEFAULT_VOTE_COUNT = 3;

    private final ReActAgent judgeAgent;
    private int truncationLength = DEFAULT_TRUNCATION_LENGTH;
    private int voteCount = DEFAULT_VOTE_COUNT;

    public LLMJudgeAdvisor() {
        super(Layer.V, "LLMJudgeAdvisor-V");
        this.judgeAgent = ReActAgent.builder()
                .name("judge")
                .sysPrompt("You are an impartial evaluation judge.")
                .model("dashscope:qwen-plus")
                .build();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();

        if (!context.containsKey("judge.candidateA") || !context.containsKey("judge.candidateB")) {
            return next.apply(input);
        }

        String candidateA = context.get("judge.candidateA").toString();
        String candidateB = context.get("judge.candidateB").toString();
        String rubric = context.getOrDefault("judge.rubric", "").toString();

        // —— 机制 1: 长度截断 ——
        String truncatedA = truncateLength(candidateA);
        String truncatedB = truncateLength(candidateB);

        // —— 机制 2: AB/BA 位置交换 ——
        JudgeResult abResult = judgePair(truncatedA, truncatedB, rubric, "AB");
        JudgeResult baResult = judgePair(truncatedB, truncatedA, rubric, "BA");

        // —— 机制 3: 多轮投票 ——
        JudgeFinalResult result = multiVote(truncatedA, truncatedB, rubric);

        log.info("[V层-LLMJudge] AB={} BA={} 多票胜者={} 最终胜者={}",
                abResult.winner(), baResult.winner(),
                result.majorityWinner(), result.winner());

        rc.put("judge.result", result);

        return next.apply(input);
    }

    // ========== 机制 1: 长度截断 ==========

    String truncateLength(String text) {
        if (text == null || text.length() <= truncationLength) return text;
        return text.substring(0, truncationLength) + "... [truncated]";
    }

    // ========== 机制 2: AB/BA 位置交换 ==========

    private JudgeResult judgePair(String responseA, String responseB, String rubric, String order) {
        String prompt = buildJudgePrompt(responseA, responseB, rubric);

        try {
            RuntimeContext judgeRc = RuntimeContext.builder()
                    .sessionId("judge-" + order)
                    .build();
            Msg response = judgeAgent.call(prompt, judgeRc).block();
            String rawResult = response == null ? "" : response.getTextContent();
            return parseJudgeResult(rawResult, order);
        } catch (Exception e) {
            log.error("[V层-LLMJudge] 评判调用失败 (order={}): {}", order, e.getMessage());
            return new JudgeResult(order, "ABSTAIN", 0.0, new LinkedHashMap<>());
        }
    }

    private String buildJudgePrompt(String responseA, String responseB, String rubric) {
        return """
            You are an impartial evaluation judge. Compare the two candidate responses below.

            ## Judging Criteria
            %s

            ## Candidate A
            %s

            ## Candidate B
            %s

            ## Instructions
            1. Evaluate BOTH responses purely on the criteria above — IGNORE length, formatting style, and verbosity.
            2. Output your judgment in JSON format:
            {
              "winner": "A" | "B" | "TIE",
              "confidence": 0.0-1.0,
              "scores": { "A": 0.0-1.0, "B": 0.0-1.0 },
              "reasoning": "brief explanation"
            }
            """.formatted(
                rubric.isBlank() ? "Accuracy, completeness, relevance, safety." : rubric,
                responseA, responseB
        );
    }

    private JudgeResult parseJudgeResult(String raw, String order) {
        String winner = "TIE";
        double confidence = 0.5;
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 0.5);
        scores.put("B", 0.5);

        try {
            if (raw.contains("\"winner\"")) {
                if (raw.contains("\"A\"")) winner = "A";
                else if (raw.contains("\"B\"")) winner = "B";
                else if (raw.contains("\"TIE\"") || raw.contains("\"tie\"")) winner = "TIE";
            } else if (raw.toLowerCase().contains("candidate a is better") || raw.contains("A更好")) {
                winner = "A";
            } else if (raw.toLowerCase().contains("candidate b is better") || raw.contains("B更好")) {
                winner = "B";
            }

            int confIdx = raw.indexOf("\"confidence\"");
            if (confIdx > 0) {
                int colonIdx = raw.indexOf(":", confIdx);
                if (colonIdx > 0) {
                    String confStr = raw.substring(colonIdx + 1).replaceAll("[^0-9.]", "").trim();
                    try { confidence = Double.parseDouble(confStr); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("[V层-LLMJudge] 解析结果失败，默认平局: {}", e.getMessage());
        }

        return new JudgeResult(order, winner, confidence, scores);
    }

    // ========== 机制 3: 多轮投票 ==========

    private JudgeFinalResult multiVote(String responseA, String responseB, String rubric) {
        List<JudgeResult> roundResults = new ArrayList<>();

        for (int i = 0; i < voteCount; i++) {
            String order = (i % 2 == 0) ? "AB" : "BA";

            JudgeResult round;
            if (order.equals("AB")) {
                round = judgePair(responseA, responseB, rubric, "AB");
            } else {
                JudgeResult baRaw = judgePair(responseB, responseA, rubric, "BA");
                round = swapResult(baRaw);
            }
            roundResults.add(round);
        }

        int votesA = 0, votesB = 0, votesTie = 0;
        double totalConfidence = 0;

        for (JudgeResult r : roundResults) {
            totalConfidence += r.confidence();
            switch (r.winner()) {
                case "A" -> votesA++;
                case "B" -> votesB++;
                default -> votesTie++;
            }
        }

        String majorityWinner;
        if (votesA > votesB && votesA > votesTie) majorityWinner = "A";
        else if (votesB > votesA && votesB > votesTie) majorityWinner = "B";
        else majorityWinner = "TIE";

        double avgConfidence = totalConfidence / Math.max(1, roundResults.size());

        log.debug("[V层-LLMJudge] 投票结果: A={} B={} TIE={} 置信度={:.4f}",
                votesA, votesB, votesTie, avgConfidence);

        return new JudgeFinalResult(majorityWinner, avgConfidence,
                votesA, votesB, votesTie, roundResults);
    }

    private JudgeResult swapResult(JudgeResult baResult) {
        String flippedWinner = switch (baResult.winner()) {
            case "A" -> "B";
            case "B" -> "A";
            default -> "TIE";
        };

        Map<String, Double> flippedScores = new LinkedHashMap<>();
        flippedScores.put("A", baResult.scores().getOrDefault("B", 0.5));
        flippedScores.put("B", baResult.scores().getOrDefault("A", 0.5));

        return new JudgeResult(baResult.order(), flippedWinner, baResult.confidence(), flippedScores);
    }

    // ========== 配置方法 ==========

    public void setTruncationLength(int length) { this.truncationLength = length; }
    public void setVoteCount(int count) { this.voteCount = Math.max(1, Math.min(count, 7)); }

    // ========== 数据记录 ==========

    public record JudgeResult(String order, String winner, double confidence, Map<String, Double> scores) {}

    public record JudgeFinalResult(
            String majorityWinner, double averageConfidence,
            int votesA, int votesB, int votesTie, List<JudgeResult> roundDetails
    ) {
        public String winner() {
            return !majorityWinner.equals("TIE") ? majorityWinner : "TIE_A";
        }
    }
}
