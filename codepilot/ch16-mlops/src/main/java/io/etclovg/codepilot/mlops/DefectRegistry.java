package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 缺陷登记表。
 * <p>对应书中 Ch16 §16.7 —— 评估与生产中发现的缺陷管理。
 * <p>集中登记缺陷的严重度、影响范围与修复状态，供发布门禁与回顾使用。
 */
@Component
public class DefectRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefectRegistry.class);

    private final List<Defect> defects = Collections.synchronizedList(new ArrayList<>());

    /**
     * 登记一个缺陷。
     *
     * @param title     缺陷标题
     * @param severity  严重度
     * @param component 受影响组件
     * @return 缺陷 ID
     */
    public String register(String title, String severity, String component) {
        String id = "defect-" + System.currentTimeMillis();
        defects.add(new Defect(id, title, severity, component, Status.OPEN));
        log.info("登记缺陷: id={}, title={}, severity={}", id, title, severity);
        return id;
    }

    /**
     * 登记缺陷并返回工单（书中 §16.4.1 Step 3 调用）。
     *
     * @param layer       根因归因的 Harness 层
     * @param description 缺陷描述
     * @param impact      业务影响
     * @return 缺陷工单（含 ID + 修复方案）
     */
    public PostmortemReport.DefectTicket register(HarnessLayer layer, String description, String impact) {
        String id = "defect-" + System.currentTimeMillis();
        defects.add(new Defect(id, description, impact, layer.name(), Status.OPEN));
        log.info("登记缺陷(层级): id={}, layer={}, impact={}", id, layer, impact);
        return new PostmortemReport.DefectTicket(id, "调整 " + layer + " 层配置 + 增补回归用例");
    }

    /**
     * 更新缺陷状态。
     *
     * @param defectId 缺陷 ID
     * @param status   新状态
     */
    public void updateStatus(String defectId, Status status) {
        defects.stream()
                .filter(d -> d.id().equals(defectId))
                .findFirst()
                .ifPresent(d -> defects.set(defects.indexOf(d),
                        new Defect(d.id(), d.title(), d.severity(), d.component(), status)));
    }

    /**
     * 获取未解决缺陷列表。
     *
     * @return 缺陷列表
     */
    public List<Defect> openDefects() {
        return defects.stream().filter(d -> d.status() == Status.OPEN).toList();
    }

    /**
     * 缺陷记录。
     *
     * @param id        缺陷 ID
     * @param title     标题
     * @param severity  严重度
     * @param component 组件
     * @param status    状态
     */
    public record Defect(String id, String title, String severity, String component, Status status) {
    }

    /**
     * 缺陷状态。
     */
    public enum Status {
        /** 待处理 */
        OPEN,
        /** 处理中 */
        IN_PROGRESS,
        /** 已解决 */
        RESOLVED,
        /** 已关闭 */
        CLOSED
    }
}
