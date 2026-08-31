package io.etclovg.codepilot.planning;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 三级回退策略注册器。
 * <p>对应书中 Ch14 §14.4.2 —— Plan A 失败后的三级回退：
 * Plan A（最优推理）→ Plan B（预定义模板方案）→ Plan C（操作指南交用户）。
 *
 * <p>与章节代码对齐：通过 taskType 查找 planBTemplates 或 planCGuides，
 * 按失败次数决定降级到 Plan B（failureCount > 0）或 Plan C（failureCount > 2）。
 */
@Component
public class ThreeTierFallback {

    /** Plan B 预定义模板（保守但可靠，不依赖 LLM 推理） */
    private final Map<String, String> planBTemplates = Map.of(
        "data-query", "SELECT * FROM {table} LIMIT 100",
        "code-fix", "// Revert to last known-good commit",
        "deploy", "// Skip canary, deploy to staging only"
    );

    /** Plan C 操作指南（交给用户，不执行任何自动操作） */
    private final Map<String, String> planCGuides = Map.of(
        "data-query", "请手动登录数据库，执行查询：{sql}",
        "code-fix", "请手动修改文件：{file}，定位方法：{method}",
        "deploy", "请通过 Jenkins 手动部署：{jobUrl}"
    );

    /**
     * 执行三级回退。
     *
     * @param taskType  任务类型（data-query / code-fix / deploy）
     * @param planAScript Plan A 的执行脚本（Plan B/C 不依赖此参数）
     * @param failureCount 失败次数：1→Plan B, 2+→Plan C
     * @return 回退结果
     */
    public FallbackResult execute(String taskType, String planAScript, int failureCount) {
        if (failureCount > 0 && planBTemplates.containsKey(taskType)) {
            return FallbackResult.of("Plan B", planBTemplates.get(taskType), 0.8);
        }
        if (failureCount > 2 && planCGuides.containsKey(taskType)) {
            return FallbackResult.of("Plan C", planCGuides.get(taskType), 1.0);
        }
        return null;
    }
}
