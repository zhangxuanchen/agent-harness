package io.etclovg.codepilot.evaluation;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.apache.commons.math3.distribution.TDistribution;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * V 层 · 回归测试执行器。
 *
 * <p>运行大规模回归测试套件，检测模型或配置变更引入的性能退化。
 * 采用双样本 t 检验确保退化信号非偶然波动。
 * 对应书中 Ch9 §9.5 — 回归测试与退化门禁。
 *
 * <h3>核心逻辑</h3>
 * <ul>
 *   <li>在 100 个任务上执行评估，对比基线分数</li>
 *   <li>当退化超过 <b>10%</b> 时标记为严重退化</li>
 *   <li>通过 Welch's t 检验验证退化统计显著性（<b>p < 0.05</b>）</li>
 *   <li>输出结构化回归报告：通过/警告/阻断</li>
 * </ul>
 */
@Component
public class RegressionRunner extends AbstractLayerMiddleware {

    private static final int SUITE_SIZE = 100;
    private static final double DEGRADATION_THRESHOLD = 0.10;
    private static final double SIGNIFICANCE_LEVEL = 0.05;
    private static final int CONCURRENCY = 4;

    private final List<BaselineRecord> baselineHistory = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
    private Function<RegressionTask, TaskResult> taskRunner;

    public RegressionRunner() {
        super(Layer.V, "RegressionRunner-V");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        Map<String, Object> context = rc.getExtra();

        if (!Boolean.parseBoolean(context.getOrDefault("regression.run", "false").toString())) {
            return next.apply(input);
        }

        String suiteId = context.getOrDefault("regression.suiteId", UUID.randomUUID().toString()).toString();
        log.info("[V层-回归] 启动回归套件 {} ({} 任务)", suiteId, SUITE_SIZE);

        RegressionReport report = runSuite(suiteId);
        rc.put("regression.report", report);

        log.info("[V层-回归] 套件 {} 完成: 状态={}", suiteId, report.verdict());

        return next.apply(input);
    }

    // ========== 回归套件核心 ==========

    public RegressionReport runSuite(String suiteId) {
        Instant startTime = Instant.now();
        List<RegressionTask> tasks = generateSuiteTasks(suiteId);

        List<Future<TaskResult>> futures = new ArrayList<>();
        AtomicInteger completed = new AtomicInteger(0);

        for (RegressionTask task : tasks) {
            futures.add(executor.submit(() -> {
                try {
                    TaskResult result = taskRunner != null
                            ? taskRunner.apply(task) : executeDefaultTask(task);
                    int done = completed.incrementAndGet();
                    if (done % 20 == 0) log.info("[V层-回归] 进度: {}/{}", done, SUITE_SIZE);
                    return result;
                } catch (Exception e) {
                    log.error("[V层-回归] 任务 {} 执行异常: {}", task.taskId(), e.getMessage());
                    return new TaskResult(task.taskId(), 0.0, false, e.getMessage());
                }
            }));
        }

        List<TaskResult> results = new ArrayList<>();
        int errors = 0;
        for (Future<TaskResult> future : futures) {
            try {
                TaskResult result = future.get(5, TimeUnit.MINUTES);
                results.add(result);
                if (!result.success()) errors++;
            } catch (TimeoutException e) {
                log.error("[V层-回归] 任务超时");
                errors++;
            } catch (Exception e) {
                log.error("[V层-回归] 任务执行失败: {}", e.getMessage());
                errors++;
            }
        }

        RegressionReport report = analyzeResults(suiteId, results, errors, startTime);
        archiveBaseline(report);
        return report;
    }

    private List<RegressionTask> generateSuiteTasks(String suiteId) {
        List<RegressionTask> tasks = new ArrayList<>(SUITE_SIZE);
        String[] categories = {"factual", "reasoning", "coding", "creative", "analysis"};

        for (int i = 0; i < SUITE_SIZE; i++) {
            String category = categories[i % categories.length];
            String question = generateTaskQuestion(i, category);
            String prompt = "## Task #" + (i + 1) + " [" + category + "]\n" + question;

            tasks.add(new RegressionTask(
                    suiteId + "-" + String.format("%03d", i + 1),
                    category, prompt, getExpectedScore(i, category)));
        }
        return tasks;
    }

    private String generateTaskQuestion(int index, String category) {
        String[][] allQuestions = {
                {"What is the capital of France?", "Who wrote '1984'?",
                        "What is the speed of light?", "Explain the water cycle.",
                        "What are the three laws of thermodynamics?"},
                {"If all A are B and all B are C, what can we conclude?",
                        "Solve: 2x + 5 = 15",
                        "A train leaves at 60mph. How far in 2.5 hours?",
                        "If 5 workers build 5 walls in 5 days...",
                        "What is the probability of rolling a sum of 7 with two dice?"},
                {"Write a function to reverse a string.", "Implement binary search.",
                        "How do you handle exceptions in Java?",
                        "Explain the difference between List and Set.",
                        "Write SQL to find duplicate records."},
                {"Write a haiku about programming.", "Describe a futuristic city.",
                        "Create a slogan for an eco-friendly product.",
                        "Write a short story about AI.",
                        "Design a logo concept for a tech startup."},
                {"Analyze the pros and cons of remote work.",
                        "Compare REST and GraphQL.",
                        "Evaluate the impact of social media.",
                        "Analyze the business model of Netflix.",
                        "Compare SQL and NoSQL databases."}
        };

        int catIndex = switch (category) {
            case "factual" -> 0; case "reasoning" -> 1; case "coding" -> 2;
            case "creative" -> 3; case "analysis" -> 4; default -> 0;
        };
        return allQuestions[catIndex][index % 5];
    }

    private double getExpectedScore(int index, String category) {
        double base = switch (category) {
            case "factual" -> 0.85; case "reasoning" -> 0.78; case "coding" -> 0.80;
            case "creative" -> 0.72; case "analysis" -> 0.75; default -> 0.78;
        };
        double noise = (Math.random() - 0.5) * 0.10;
        return Math.min(1.0, Math.max(0.3, base + noise));
    }

    private TaskResult executeDefaultTask(RegressionTask task) {
        double score = task.expectedScore() * (0.9 + Math.random() * 0.2);
        return new TaskResult(task.taskId(), score, true, null);
    }

    // ========== 统计分析 ==========

    RegressionReport analyzeResults(String suiteId, List<TaskResult> results,
                                     int errors, Instant startTime) {
        if (results.isEmpty()) {
            return new RegressionReport(suiteId, "ERROR", 0, 0, 0, 1.0,
                    0, errors, Duration.between(startTime, Instant.now()));
        }

        double[] currentScores = results.stream()
                .filter(TaskResult::success).mapToDouble(TaskResult::score).toArray();
        double currentMean = Arrays.stream(currentScores).average().orElse(0);

        double[] baselineScores = getBaselineScores(suiteId);
        double baselineMean = baselineScores.length > 0
                ? Arrays.stream(baselineScores).average().orElse(currentMean) : currentMean;

        double degradation = baselineMean > 0
                ? (baselineMean - currentMean) / baselineMean : 0;
        double pValue = welchTTest(baselineScores, currentScores);

        String verdict;
        if (degradation > DEGRADATION_THRESHOLD && pValue < SIGNIFICANCE_LEVEL) {
            verdict = "BLOCK";
        } else if (degradation > 0.05 || pValue < 0.10) {
            verdict = "WARN";
        } else {
            verdict = "PASS";
        }

        int passedTasks = (int) results.stream().filter(r -> r.score() >= 0.6).count();
        Duration duration = Duration.between(startTime, Instant.now());

        log.info("[V层-回归] 分析: 均值={:.4f} 基线={:.4f} 退化={:.2%} p={:.4f} → {}",
                currentMean, baselineMean, degradation, pValue, verdict);

        return new RegressionReport(suiteId, verdict, currentMean, baselineMean,
                degradation, pValue, passedTasks, errors, duration);
    }

    private double welchTTest(double[] sample1, double[] sample2) {
        if (sample1.length < 2 || sample2.length < 2) return 1.0;

        double mean1 = Arrays.stream(sample1).average().orElse(0);
        double mean2 = Arrays.stream(sample2).average().orElse(0);
        double var1 = variance(sample1, mean1);
        double var2 = variance(sample2, mean2);

        if (var1 == 0 && var2 == 0) return 1.0;

        double se = Math.sqrt(var1 / sample1.length + var2 / sample2.length);
        if (se == 0) return 1.0;

        double t = (mean1 - mean2) / se;

        double dfNum = Math.pow(var1 / sample1.length + var2 / sample2.length, 2);
        double dfDen = Math.pow(var1 / sample1.length, 2) / (sample1.length - 1)
                     + Math.pow(var2 / sample2.length, 2) / (sample2.length - 1);
        double df = dfDen > 0 ? dfNum / dfDen : sample1.length + sample2.length - 2;
        df = Math.max(1, Math.min(df, 1000));

        try {
            TDistribution tDist = new TDistribution(df);
            return 1.0 - tDist.cumulativeProbability(Math.abs(t));
        } catch (Exception e) {
            log.warn("[V层-回归] t检验计算失败: {}", e.getMessage());
            return 1.0;
        }
    }

    private double variance(double[] values, double mean) {
        if (values.length < 2) return 0;
        double sumSq = 0;
        for (double v : values) sumSq += Math.pow(v - mean, 2);
        return sumSq / (values.length - 1);
    }

    private double[] getBaselineScores(String suiteId) {
        synchronized (baselineHistory) {
            return baselineHistory.stream()
                    .filter(b -> b.suiteId().startsWith(suiteId.split("-")[0]))
                    .mapToDouble(BaselineRecord::meanScore).toArray();
        }
    }

    private void archiveBaseline(RegressionReport report) {
        synchronized (baselineHistory) {
            baselineHistory.add(new BaselineRecord(report.suiteId(), report.currentMean(), Instant.now()));
            if (baselineHistory.size() > 1000) baselineHistory.subList(0, 100).clear();
        }
    }

    public void setTaskRunner(Function<RegressionTask, TaskResult> runner) { this.taskRunner = runner; }

    // ========== 数据记录 ==========

    public record RegressionTask(String taskId, String category, String prompt, double expectedScore) {}
    public record TaskResult(String taskId, double score, boolean success, String errorMessage) {}
    public record BaselineRecord(String suiteId, double meanScore, Instant recordedAt) {}

    public record RegressionReport(
            String suiteId, String verdict, double currentMean, double baselineMean,
            double degradationRate, double pValue, int passedTasks,
            int errors, Duration duration
    ) {
        public boolean gatePassed() { return "PASS".equals(verdict); }
        public boolean isSignificant() { return pValue < SIGNIFICANCE_LEVEL; }
        public String summary() {
            return String.format("[%s] 均值: %.4f (基线: %.4f) 退化: %.2f%% p=%.4f %d通过 %s",
                    verdict, currentMean, baselineMean, degradationRate * 100, pValue, passedTasks, duration);
        }
    }
}
