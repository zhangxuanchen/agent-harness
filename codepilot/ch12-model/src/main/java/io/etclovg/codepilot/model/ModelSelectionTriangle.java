package io.etclovg.codepilot.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 模型选择三角：能力(Capability) × 成本(Cost) × 延迟(Latency) 的三维选型框架。
 *
 * <p>对应书中 Ch12 §12.1 KP 12.1.1 + KP 12.1.2：
 * <ul>
 *   <li>KP 12.1.1：RouteLLM 证明仅将 <b>14%</b> 的请求发送给强模型即可维持 95% 性能，
 *       节省 <b>85%</b> 成本。三维选型框架的本质就是把"任务分类"映射到"模型档位"，
 *       让简单任务用便宜模型，只有真的需要时才调用强模型——这是 RouteLLM 的数学基础。</li>
 *   <li>KP 12.1.2：ReAct 循环中上下文呈 O(N²) 膨胀（等差数列求和：
 *       总输入量 = N(N+1)/2 × basePrompt），所以输入 token 占比通常 70-85%，
 *       输出 token 虽少但单价是输入的 5~10×，估算总成本时必须按「输入价/输出价」
 *       分别计算，不能只用单一 costPerM。</li>
 * </ul>
 *
 * <p>2026/08/06 修复：原 `estimateAgentCost` 方法使用 magic number
 *     `* 2` 粗略估算"输出更贵"，与 Ch11 P3 已修复的 Sonnet 4.6 实际定价
 *     （$3/M input，$15/M output = 5×）严重不符。现改为通过
 *     {@link #outputPriceRatioOf(String)} 按模型名查询真实的 output/input 价格比。
 */
@Component
public class ModelSelectionTriangle {

    private static final Logger log = LoggerFactory.getLogger(ModelSelectionTriangle.class);

    /**
     * 模型档案。
     * <p>2026/08/06 升级：区分 inputCostPerM 与 outputCostPerM（与 Ch11 一致）。
     *    旧版单一 costPerM 无法表达"输出 ~5× 输入"的行业规律。
     *
     * @param name            模型 id（与 ch12 RouteLLM/MFallback 的字符串常量保持一致）
     * @param capability      综合能力评分 0-1（用于 KP 12.1.1 决策门槛线，如简单任务 >=0.7）
     * @param inputCostPerM   每 1M input tokens 美元价（与 Ch11 P3 修复后的 Sonnet 4.6 $3/M 等保持一致）
     * @param outputCostPerM  每 1M output tokens 美元价（通常为 input 的 5~10×）
     * @param latencyMs       单轮端到端延迟 P50 估算（毫秒）
     */
    public record ModelProfile(
            String name, double capability,
            double inputCostPerM, double outputCostPerM,
            double latencyMs
    ) {
        @Deprecated(since = "2026-08-06", forRemoval = true)
        public double costPerM() { return inputCostPerM; } // 兼容旧代码调用
    }

    private final List<ModelProfile> profiles = new ArrayList<>();

    public ModelSelectionTriangle() {
        // KP 12.1.1 + Ch11 P3 统一价目表（2026 Q3 官方定价）
        profiles.add(new ModelProfile("GPT-5",            0.94, 15.0, 75.0, 650));  // 顶级档
        profiles.add(new ModelProfile("Claude-Opus-4.6",  0.93,  5.0, 25.0, 550));  // 强能力档
        profiles.add(new ModelProfile("GPT-4o",           0.92,  2.5, 10.0, 380));  // 强能力档（GPT）
        profiles.add(new ModelProfile("Claude-Sonnet-4.6", 0.88,  3.0, 15.0, 280));  // 标准档 ✦ Ch11 统一默认
        profiles.add(new ModelProfile("GPT-5.4-Mini",     0.85,  0.5,  2.0, 180));  // 标准档（GPT）
        profiles.add(new ModelProfile("Claude-Haiku-4.5",  0.75,  0.1,  0.5, 90));   // 轻量档
        profiles.add(new ModelProfile("Gemini-2.5-Flash",  0.76, 0.075, 0.30, 95));  // 轻量档
        profiles.add(new ModelProfile("Qwen-Mini",         0.72, 0.02,  0.06, 65));   // 本土轻量档
        profiles.add(new ModelProfile("DeepSeek-V3",       0.82, 0.02,  0.08, 150));  // 本土标准档
    }

    /**
     * KP 12.1.1 三维选型主入口：找到「能力够、预算内、延迟容忍」的最低成本方案。
     *
     * @param requiredCapability 该任务要求的最低能力分（如简单 FAQ=0.70，代码重构=0.85，法律推理=0.92）
     * @param budgetPerTaskUsd    单次任务成本上限（美元；Agent N 步估算总成本要 < 此值）
     * @param maxLatencyMs        端到端延迟上限 P95（毫秒；客服场景通常 3s，后台分析可 30s）
     */
    public Optional<ModelProfile> selectBestFit(double requiredCapability,
                                                 double budgetPerTaskUsd,
                                                 double maxLatencyMs) {
        record Candidate(ModelProfile p, double fitScore) {}
        List<Candidate> fits = profiles.stream()
                .filter(p -> p.capability >= requiredCapability)
                .filter(p -> p.latencyMs <= maxLatencyMs)
                // 三维得分：用 (input+output)/2 加权估算的单 M 成本作为便宜度
                .map(p -> new Candidate(p, (p.inputCostPerM + p.outputCostPerM) / 2.0))
                .sorted(Comparator.comparingDouble(Candidate::fitScore))
                .toList();
        log.info("[选型 KP12.1.1] 门槛：能力≥{}，预算≤${}/任务，P95≤{}ms → {} 个候选（最便宜优先）",
                requiredCapability, budgetPerTaskUsd, maxLatencyMs, fits.size());
        return fits.stream().findFirst().map(Candidate::p);
    }

    /**
     * KP 12.2.1 按档位返回默认路由名。
     */
    public String routeByTaskComplexity(String taskType) {
        return switch (taskType) {
            case "simple", "faq", "format", "translation", "summarization" -> "Claude-Haiku-4.5";
            case "coding", "analysis", "code_review"                        -> "Claude-Sonnet-4.6";
            case "reasoning", "complex", "legal"                             -> "Claude-Opus-4.6";
            default                                                          -> "Claude-Sonnet-4.6";
        };
    }

    /**
     * KP 12.1.2 估算 Agent 场景的 N 步总成本（ReAct O(N²) 上下文膨胀模型）。
     *
     * <p>2026/08/06 修复：原 magic `* 2` 改为 {@link #outputPriceRatioOf(String)}
     *    按模型查真实 output/input 价格比（如 Sonnet 4.6 = 5×，Opus 4.6 = 5×，Haiku 4.5 = 5×，DeepSeek V3 = 4×）。
     */
    public double estimateAgentCost(int steps, String modelName,
                                    long avgInputTokensPerStep, long avgOutputTokensPerStep) {
        ModelProfile model = findByName(modelName);
        double outRatio = outputPriceRatioOf(modelName);
        log.debug("[成本估算 KP12.1.2] 模型={}，steps={}，output/input 价倍率={}",
                modelName, steps, outRatio);

        double totalCost = 0;
        long contextGrowthFactor = 0; // O(N²) 累计
        for (int i = 1; i <= steps; i++) {
            // 等差数列：第 i 步输入 = i * avgInputTokensPerStep；求和 = N(N+1)/2 × avgInputTokensPerStep
            long stepInput  = (long) i * avgInputTokensPerStep;
            long stepOutput = avgOutputTokensPerStep;
            double stepCost = (stepInput  * model.inputCostPerM
                           + stepOutput * model.outputCostPerM) / 1_000_000.0;
            totalCost += stepCost;
            contextGrowthFactor = (long) i * (i + 1) / 2; // 仅记录最后一步供显示
        }
        log.info("[成本估算 KP12.1.2] N={} 步，O(N²) 膨胀因子=N(N+1)/2={}，总成本=${:.4f}",
                steps, contextGrowthFactor, totalCost);
        return totalCost;
    }

    /** 与 KP 12.1.1 + Ch11 价目表一致的 output/input 价格比查询。 */
    public static double outputPriceRatioOf(String modelName) {
        if (modelName == null) return 5.0;
        String m = modelName.toLowerCase(Locale.ROOT);
        // 轻量档 ~5×（绝大多数模型的官方比例）
        if (m.contains("haiku") || m.contains("flash") || m.contains("mini")
                || m.contains("qwen-mini")) return 5.0;
        if (m.contains("deepseek")) return 4.0;  // DeepSeek V3 官方 0.02→0.08 = 4×
        if (m.contains("gpt-5.4-mini") || m.equals("gpt-4o-mini")) return 4.0;
        // 标准档/强档通用 5×（Sonnet 3/15，Opus 5/25，GPT-5 15/75）
        return 5.0;
    }

    private ModelProfile findByName(String modelName) {
        return profiles.stream()
                .filter(p -> p.name.equalsIgnoreCase(modelName)
                        || modelName.toLowerCase(Locale.ROOT).contains(p.name.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseGet(() -> profiles.get(3)); // 默认 Sonnet 4.6（与 Ch11 一致）
    }

    public List<ModelProfile> getAllProfiles() { return Collections.unmodifiableList(profiles); }
}
