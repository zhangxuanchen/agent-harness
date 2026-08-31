package io.etclovg.codepilot.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O 层 · Harness 假设注册表。
 *
 * <p>实现"Harness 即假设"理念的核心组件，注册和管理所有 Advisor 的隐含假设。
 * 对应书中 Ch8 §8.5 假设漂移检测设计。
 *
 * <p><b>核心理念</b>：
 * <ul>
 *   <li>每个 Advisor 都基于某些假设工作（如"沙箱隔离率 100%"）</li>
 *   <li>这些假设需要被显式声明和追踪</li>
 *   <li>当假设失效或漂移时，需要及时检测和告警</li>
 * </ul>
 *
 * <p><b>假设类型</b>：
 * <ul>
 *   <li><b>安全假设</b>：关于系统安全性的假设（如"输入已验证"）</li>
 *   <li><b>性能假设</b>：关于性能的假设（如"平均延迟 < 100ms"）</li>
 *   <li><b>可靠性假设</b>：关于可靠性的假设（如"成功率 > 99%"）</li>
 *   <li><b>成本假设</b>：关于成本的假设（如"单次调用成本 < $0.01"）</li>
 * </ul>
 *
 * <p><b>页面参考</b>：Ch8 §8.5 Harness 即假设理念
 */
@Component
public class HarnessAssumptionRegistry {

    private static final Logger log = LoggerFactory.getLogger(HarnessAssumptionRegistry.class);

    /** Advisor名称 → 假设集合 */
    private final Map<String, Set<Assumption>> advisorAssumptions = new ConcurrentHashMap<>();

    /** 假设ID → 假设详情 */
    private final Map<String, Assumption> assumptionRegistry = new ConcurrentHashMap<>();

    /** 假设ID → 验证历史 */
    private final Map<String, List<AssumptionValidation>> validationHistory = new ConcurrentHashMap<>();

    public HarnessAssumptionRegistry() {
        log.info("[O层·假设注册表] HarnessAssumptionRegistry 已初始化");
    }

    // ==================== 假设注册方法 ====================

    /**
     * 注册一个假设。
     *
     * @param advisorName  Advisor 名称
     * @param assumptionId 假设ID
     * @param description  假设描述
     * @param type         假设类型
     * @param threshold    假设阈值（用于验证）
     * @return 注册的假设对象
     */
    public Assumption registerAssumption(String advisorName, String assumptionId,
                                         String description, AssumptionType type,
                                         double threshold) {
        Assumption assumption = new Assumption(
                assumptionId,
                advisorName,
                description,
                type,
                threshold,
                Instant.now(),
                true
        );

        // 注册到全局注册表
        assumptionRegistry.put(assumptionId, assumption);

        // 关联到 Advisor
        advisorAssumptions.computeIfAbsent(advisorName, k -> ConcurrentHashMap.newKeySet())
                .add(assumption);

        log.info("[O层·假设注册表] 注册假设: {} -> {} [{}]", advisorName, assumptionId, type);

        return assumption;
    }

    /**
     * 批量注册假设。
     */
    public void registerAssumptions(String advisorName, Assumption... assumptions) {
        for (Assumption assumption : assumptions) {
            registerAssumption(advisorName, assumption.id(), assumption.description(),
                    assumption.type(), assumption.threshold());
        }
    }

    /**
     * 获取指定 Advisor 的所有假设。
     */
    public Set<Assumption> getAssumptionsByAdvisor(String advisorName) {
        return Collections.unmodifiableSet(
                advisorAssumptions.getOrDefault(advisorName, Collections.emptySet())
        );
    }

    /**
     * 获取指定类型的所有假设。
     */
    public List<Assumption> getAssumptionsByType(AssumptionType type) {
        return assumptionRegistry.values().stream()
                .filter(a -> a.type() == type)
                .toList();
    }

    /**
     * 获取所有活跃的假设。
     */
    public List<Assumption> getAllActiveAssumptions() {
        return assumptionRegistry.values().stream()
                .filter(Assumption::active)
                .toList();
    }

    // ==================== 假设验证方法 ====================

    /**
     * 记录一次假设验证结果。
     *
     * @param assumptionId 假设ID
     * @param actualValue  实际观测值
     * @param valid        是否有效
     */
    public void recordValidation(String assumptionId, double actualValue, boolean valid) {
        Assumption assumption = assumptionRegistry.get(assumptionId);
        if (assumption == null) {
            log.warn("[O层·假设注册表] 未找到假设: {}", assumptionId);
            return;
        }

        AssumptionValidation validation = new AssumptionValidation(
                assumptionId,
                actualValue,
                assumption.threshold(),
                valid,
                Instant.now()
        );

        // 记录验证历史
        validationHistory.computeIfAbsent(assumptionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(validation);

        log.debug("[O层·假设注册表] 记录验证: {} = {} (阈值={}, 有效={})",
                assumptionId, actualValue, assumption.threshold(), valid);
    }

    /**
     * 获取假设的验证历史。
     */
    public List<AssumptionValidation> getValidationHistory(String assumptionId) {
        return Collections.unmodifiableList(
                validationHistory.getOrDefault(assumptionId, Collections.emptyList())
        );
    }

    /**
     * 获取假设的最近验证结果。
     */
    public Optional<AssumptionValidation> getLatestValidation(String assumptionId) {
        List<AssumptionValidation> history = validationHistory.get(assumptionId);
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(history.get(history.size() - 1));
    }

    // ==================== 假设管理方法 ====================

    /**
     * 停用一个假设。
     */
    public void deactivateAssumption(String assumptionId) {
        Assumption assumption = assumptionRegistry.get(assumptionId);
        if (assumption != null) {
            Assumption updated = new Assumption(
                    assumption.id(),
                    assumption.advisorName(),
                    assumption.description(),
                    assumption.type(),
                    assumption.threshold(),
                    assumption.registeredAt(),
                    false
            );
            assumptionRegistry.put(assumptionId, updated);
            log.warn("[O层·假设注册表] 假设已停用: {}", assumptionId);
        }
    }

    /**
     * 更新假设阈值。
     */
    public void updateAssumptionThreshold(String assumptionId, double newThreshold) {
        Assumption assumption = assumptionRegistry.get(assumptionId);
        if (assumption != null) {
            Assumption updated = new Assumption(
                    assumption.id(),
                    assumption.advisorName(),
                    assumption.description(),
                    assumption.type(),
                    newThreshold,
                    assumption.registeredAt(),
                    assumption.active()
            );
            assumptionRegistry.put(assumptionId, updated);
            log.info("[O层·假设注册表] 假设阈值已更新: {} -> {}", assumptionId, newThreshold);
        }
    }

    /**
     * 获取假设总数。
     */
    public int getTotalAssumptionCount() {
        return assumptionRegistry.size();
    }

    /**
     * 获取活跃假设数量。
     */
    public int getActiveAssumptionCount() {
        return (int) assumptionRegistry.values().stream()
                .filter(Assumption::active)
                .count();
    }

    // ==================== 内部类型 ====================

    /** 假设类型 */
    public enum AssumptionType {
        SECURITY,    // 安全假设
        PERFORMANCE, // 性能假设
        RELIABILITY, // 可靠性假设
        COST         // 成本假设
    }

    /** 假设定义 */
    public record Assumption(
            String id,
            String advisorName,
            String description,
            AssumptionType type,
            double threshold,
            Instant registeredAt,
            boolean active
    ) {
        @Override
        public String toString() {
            return String.format("Assumption[id=%s, advisor=%s, type=%s, threshold=%.2f, active=%s]",
                    id, advisorName, type, threshold, active);
        }
    }

    /** 假设验证记录 */
    public record AssumptionValidation(
            String assumptionId,
            double actualValue,
            double expectedThreshold,
            boolean valid,
            Instant timestamp
    ) {
        @Override
        public String toString() {
            return String.format("Validation[assumption=%s, actual=%.2f, expected=%.2f, valid=%s]",
                    assumptionId, actualValue, expectedThreshold, valid);
        }
    }
}