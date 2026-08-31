package io.etclovg.codepilot.mlops;

import java.util.ArrayList;
import java.util.List;

/**
 * 过渡建议生成器。对应书中 Ch16 §16.6.1。
 * <p>据差距分析结果生成具体的下一步行动方案（{@link ActionItem} 列表）。
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 */
public final class TransitionMiddleware {

    private TransitionMiddleware() {
    }

    /** 据差距与优先级生成行动建议。 */
    public static List<ActionItem> recommend(GapAnalysis gap, String priority) {
        List<ActionItem> actions = new ArrayList<>();
        actions.add(new ActionItem("补齐 " + gap.dimension() + " 至目标 " + gap.target(),
                priority, "当前 " + gap.current() + "，差距 " + gap.gap()));
        return actions;
    }
}
