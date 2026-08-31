package io.etclovg.codepilot.multiagent;

import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;

/**
 * LLM 评判仲裁器。对应书中 Ch15 §15.3 Phase 3。
 * Agent 结果分歧时，用独立 LLM 评分选出最优。
 * 桩实现：按 payload 长度 + 关键字打分。概念示例。
 */
@Component
public class LlmJudge {
    public ConsistencyVerifier.AgentOutput selectBest(List<ConsistencyVerifier.AgentOutput> results) {
        return results.stream().max(Comparator.comparingInt(o -> score(o))).orElse(null);
    }
    private int score(ConsistencyVerifier.AgentOutput o) {
        String s = String.valueOf(o.payload());
        int sc = s.length();
        if (s.contains("结论") || s.contains("result")) sc += 100;
        if (s.contains("验证") || s.contains("verify")) sc += 50;
        return sc;
    }
}
