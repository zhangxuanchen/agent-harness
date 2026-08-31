package io.etclovg.codepilot.mlops;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 评估集注册表。对应书中 Ch16 §16.2.1 / §16.4.1。
 * <p>加载回归任务集（按规模 N）供 EvalGate 并行运行基线/候选版本对比；
 * 事故复盘产出的回归用例通过 {@link #addAll} 注入，进入后续版本的 Eval Gate 回归套件
 * （48 小时反馈闭环的关键一环——失败 case 沉淀为回归用例）。
 */
@Component
public class EvalRegistry {

    private final List<EvalCase> regressionCases = new ArrayList<>();

    /** 加载 N 条回归任务（生产中从持久化评估集加载，此处返回桩数据）。 */
    public static List<EvalTask> loadRegressionSuite(int n) {
        List<EvalTask> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(new EvalTask("reg-" + i, "回归用例 #" + i, null));
        }
        return tasks;
    }

    /** 将事故复盘产出的回归用例注入回归套件（书中 §16.4.1 {@code evalRegistry.addAll}）。 */
    public void addAll(List<EvalCase> cases) {
        if (cases != null) {
            regressionCases.addAll(cases);
        }
    }

    /** 当前回归套件规模。 */
    public int size() {
        return regressionCases.size();
    }
}
