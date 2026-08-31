package io.etclovg.codepilot.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import io.etclovg.codepilot.core.Layer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 五区预算分配器（C 层中间件）。
 *
 * <p>配套仓库教学实现，非 AgentScope 内置。
 *
 * <p>对应书中 KP 6.1.1 五区预算分配 —— 将上下文窗口按用途划分为五个区，
 * 每个区有独立的 token 预算比例，避免工具历史膨胀挤占系统提示空间。
 * 核心思想是"分区而治"：高价值上下文（系统提示/任务描述/记忆检索）有保底预算，
 * 工具历史即便膨胀也只能在自身区内消化，防止越界侵蚀。
 */
@Component
public class FiveZoneBudgetAllocator extends AbstractLayerMiddleware {

    /** 工具历史膨胀告警阈值：实际占比超过 45% 即告警（高于分配的 40%） */
    private static final double TOOL_HISTORY_OVERFLOW_THRESHOLD = 0.45;

    /** 自由空间不足告警阈值：实际占比低于 15% 即告警 */
    private static final double FREE_SPACE_FLOOR_THRESHOLD = 0.15;

    public FiveZoneBudgetAllocator() {
        super(Layer.C, "FiveZoneBudgetAllocator");
    }

    /**
     * Acting 阶段：每次工具执行触发预算复算观测。
     *
     * <p>工具执行是上下文膨胀的主要驱动因素，在此阶段记录工具名便于
     * 后续按工具维度归因预算消耗。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        // 工具名从 RuntimeContext 取（与全书 T/C 层中间件一致）
        String toolName = rc.getExtra().getOrDefault("tool.name", "unknown").toString();
        log.debug("[FiveZoneBudget] 工具[{}]触发预算复算观测", toolName);
        return next.apply(input);
    }

    /**
     * 按比例分配各区 token 预算。
     *
     * <p>为什么这样做：五区固定比例让"系统提示/任务描述/记忆检索"这类高价值
     * 上下文获得保底预算，工具历史即便膨胀也只能在自身区内消化，防止越界。
     * 向下取整保证各区预算之和不超过总预算。
     *
     * @param totalTokens 上下文总预算（token）
     * @return 各区分配到的 token 预算（按枚举声明顺序）
     */
    public Map<Zone, Integer> allocate(int totalTokens) {
        Map<Zone, Integer> allocated = new LinkedHashMap<>();
        for (Zone zone : Zone.values()) {
            // 按百分比分配，向下取整保证不超额
            int budget = (int) (totalTokens * zone.percentage);
            allocated.put(zone, budget);
        }
        return allocated;
    }

    /**
     * 检查实际使用是否超过分配预算。
     *
     * <p>为什么这样做：工具历史是上下文膨胀的主要源头，一旦其实际占比超过 45%
     * （高于分配的 40%）说明开始"吃"自由空间；自由空间低于 15% 说明缓冲耗尽，
     * 需要触发压缩或摘要——这两类信号是五区动态博弈的关键观测点。
     *
     * @param allocated 分配预算
     * @param actual    实际使用
     * @return 告警说明（无告警返回 "OK"）
     */
    public String checkOverflow(Map<Zone, Integer> allocated, Map<Zone, Integer> actual) {
        if (allocated == null || actual == null) {
            return "OK";
        }
        int total = allocated.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return "OK";
        }

        // 检查 1：工具历史实际占比是否超过 45%
        int toolActual = actual.getOrDefault(Zone.TOOL_HISTORY, 0);
        double toolRatio = (double) toolActual / total;
        if (toolRatio > TOOL_HISTORY_OVERFLOW_THRESHOLD) {
            return String.format(
                    "WARN: 工具历史实际占比 %.1f%% 超过 45%% 阈值，开始侵蚀自由空间",
                    toolRatio * 100);
        }

        // 检查 2：自由空间实际占比是否低于 15%
        int freeActual = actual.getOrDefault(Zone.FREE_SPACE, 0);
        double freeRatio = (double) freeActual / total;
        if (freeRatio < FREE_SPACE_FLOOR_THRESHOLD) {
            return String.format(
                    "WARN: 自由空间实际占比 %.1f%% 低于 15%% 下限，缓冲耗尽需压缩",
                    freeRatio * 100);
        }

        return "OK";
    }

    /**
     * 返回五区动态博弈说明。
     *
     * <p>为什么这样做：新手常误以为上下文超限时应该压缩系统提示，实际上系统提示
     * 占比仅 10% 且是 KV-cache 命中的稳定前缀，真正会膨胀的是工具历史（40%），
     * 它吃的是自由空间（30%）而不是系统提示。这条说明用于纠正该误区，
     * 指明正确的压缩方向。
     *
     * @return 动态博弈说明文本
     */
    public String getDynamicAdversionNote() {
        return "五区动态博弈：工具历史是主要膨胀源，膨胀时吃的是自由空间（30%），"
                + "而非系统提示（10%）。系统提示作为 KV-cache 稳定前缀不应被压缩；"
                + "正确做法是压缩/摘要工具历史，把空间还给自由空间区。";
    }

    /**
     * 五区枚举。
     *
     * <p>每区带百分比与用途说明，比例之和为 100%。百分比以小数存储（0.10 = 10%），
     * 便于 {@link #allocate(int)} 直接相乘。
     */
    public enum Zone {
        /** 系统提示区：人格、规则、工具清单，KV-cache 稳定前缀 */
        SYSTEM_PROMPT(0.10, "系统提示：人格/规则/工具清单"),
        /** 任务描述区：当前用户目标与约束 */
        TASK_DESCRIPTION(0.05, "任务描述：当前目标与约束"),
        /** 记忆检索区：RAG / episodic 召回片段 */
        MEMORY_RETRIEVAL(0.15, "记忆检索：RAG 召回片段"),
        /** 工具历史区：工具调用与返回，最易膨胀 */
        TOOL_HISTORY(0.40, "工具历史：调用与返回，主要膨胀源"),
        /** 自由空间区：缓冲，被工具历史侵蚀时告警 */
        FREE_SPACE(0.30, "自由空间：缓冲，被侵蚀即告警");

        private final double percentage;
        private final String description;

        Zone(double percentage, String description) {
            this.percentage = percentage;
            this.description = description;
        }

        /** 该区占总预算的比例（0.0-1.0） */
        public double percentage() {
            return percentage;
        }

        /** 该区用途说明 */
        public String description() {
            return description;
        }
    }
}
