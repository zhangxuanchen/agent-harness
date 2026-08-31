package io.etclovg.codepilot.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Tree of Thoughts 中间件（L 层）。
 * <p>对应书中 Ch14 §14.1 KP 14.1.3 —— ToT 范式实现。
 * 束搜索（Beam Search）：每层生成 width 个候选 → 评估 → 保留 top-k → 重复 depth 层。
 *
 * <p>成本特征：每次调用独立（不累积上下文），总调用次数 = Σ width^i。
 * 适合 >12 步 + ≥3 候选方案的复杂探索任务。
 *
 * <p>与章节代码对齐：流式 builder 模式，支持 .generate / .evaluate / .beamWidth /
 * .maxDepth / .build()。
 */
@Component
public class ToTMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ToTMiddleware.class);

    private Object agent;
    private Function<String, List<Thought>> generateFn;   // 生成候选
    private Function<Thought, Double> evaluateFn;          // 评估候选（0.0-1.0）
    private int beamWidth = 3;                              // 束宽（每层保留 top-k）
    private int maxDepth = 2;                               // 最大搜索深度

    public Builder builder() {
        return new Builder(this);
    }

    public static class Builder {
        private final ToTMiddleware middleware;

        Builder(ToTMiddleware middleware) {
            this.middleware = middleware;
        }

        public Builder agent(Object agent) {
            middleware.agent = agent;
            return this;
        }

        public Builder generate(Function<String, List<Thought>> fn) {
            middleware.generateFn = fn;
            return this;
        }

        public Builder evaluate(Function<Thought, Double> fn) {
            middleware.evaluateFn = fn;
            return this;
        }

        public Builder beamWidth(int width) {
            middleware.beamWidth = width;
            return this;
        }

        public Builder maxDepth(int depth) {
            middleware.maxDepth = depth;
            return this;
        }

        public ToTMiddleware build() {
            return middleware;
        }
    }

    /**
     * 思考节点（ToT 树的一个候选）。
     */
    public record Thought(String content, double score, Thought parent) {

        /**
         * 构建从根到当前节点的路径（用于最终答案合成）。
         */
        public String path() {
            List<String> chain = new ArrayList<>();
            Thought current = this;
            while (current != null) {
                chain.add(0, current.content);
                current = current.parent;
            }
            return String.join(" → ", chain);
        }
    }

    /**
     * 执行束搜索。
     *
     * @param task 任务描述
     * @return 最优路径的最终答案
     */
    public String execute(String task) {
        // 初始层：从任务生成候选
        List<Thought> beam = generateFn.apply(task).stream()
            .map(t -> new Thought(t.content(), 0.0, null))
            .toList();
        log.info("ToT depth 0: 生成 {} 个候选", beam.size());

        // 评估初始层并保留 top-k
        beam = evaluateAndSort(beam)
            .subList(0, Math.min(beam.size(), beamWidth));

        // 逐层展开
        for (int depth = 1; depth <= maxDepth; depth++) {
            List<Thought> nextBeam = new ArrayList<>();

            for (Thought parent : beam) {
                // 每个父节点生成候选（不累积上下文——每次独立调用）
                List<Thought> children = generateFn.apply(parent.content()).stream()
                    .map(t -> new Thought(t.content(), 0.0, parent))
                    .toList();
                nextBeam.addAll(children);
            }

            // 评估并剪枝：保留 top-k
            beam = evaluateAndSort(nextBeam)
                .subList(0, Math.min(nextBeam.size(), beamWidth));
            log.info("ToT depth {}: 评估 {} 个候选，保留 top-{}", depth, nextBeam.size(), beam.size());
        }

        // 返回最优路径
        if (beam.isEmpty()) {
            return "ToT 搜索未产生有效结果";
        }
        Thought best = beam.get(0);
        log.info("ToT 完成，最优评分: {}", best.score());
        return best.path();
    }

    /**
     * 评估候选并按评分降序排序。
     */
    private List<Thought> evaluateAndSort(List<Thought> thoughts) {
        return thoughts.stream()
            .map(t -> new Thought(t.content(), evaluateFn.apply(t), t.parent()))
            .sorted(Comparator.comparingDouble(Thought::score).reversed())
            .toList();
    }
}
