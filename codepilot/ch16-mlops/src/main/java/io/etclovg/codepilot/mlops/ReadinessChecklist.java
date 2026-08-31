package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 就绪检查清单。
 * <p>对应书中 Ch16 §16.3 —— 发布前的就绪检查项汇总。
 * <p>汇总所有必检项（评估、性能、策略、人工审批）的通过状态，
 * 全部通过才允许发布。
 */
@Component
public class ReadinessChecklist {

    private static final Logger log = LoggerFactory.getLogger(ReadinessChecklist.class);

    /**
     * 评估就绪状态。
     *
     * @param items 各检查项状态
     * @return 全部通过返回 true
     */
    public boolean isReady(List<CheckItem> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        boolean ready = items.stream().allMatch(CheckItem::passed);
        log.info("就绪检查: items={}, ready={}", items.size(), ready);
        return ready;
    }

    /**
     * 阶段转换就绪检查：列出从当前阶段迈向目标阶段需满足的就绪项。
     *
     * @param current   当前阶段
     * @param target    目标阶段
     * @param projectId 项目 ID
     * @return 就绪项列表（含已满足与未满足）
     */
    public List<ReadinessItem> check(Stage current, Stage target, String projectId) {
        log.info("阶段转换就绪检查: project={}, {} -> {}", projectId, current, target);
        return List.of(
                new ReadinessItem("eval-coverage", current.ordinal() >= Stage.PILOT.ordinal(), "评估覆盖度"),
                new ReadinessItem("observability", current.ordinal() >= Stage.PILOT.ordinal(), "可观测性"),
                new ReadinessItem("rollback-auto", target.ordinal() >= Stage.SCALE.ordinal(), "自动回滚"));
    }

    /**
     * 检查项。
     *
     * @param name    检查项名称
     * @param passed  是否通过
     * @param detail  详情
     */
    public record CheckItem(String name, boolean passed, String detail) {
    }
}
