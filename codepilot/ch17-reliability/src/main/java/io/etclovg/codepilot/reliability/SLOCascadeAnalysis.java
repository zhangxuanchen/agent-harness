package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SLO 级联分析：分析 SLA 失败的级联影响
 * 对应书中 Ch17 §17.3 —— SLA 级联故障分析
 */
@Component
public class SLOCascadeAnalysis {

    private static final Logger log = LoggerFactory.getLogger(SLOCascadeAnalysis.class);

    private final Map<String, SLOComponent> components = new ConcurrentHashMap<>();
    private final Map<String, List<String>> dependencyGraph = new ConcurrentHashMap<>();

    public void registerComponent(SLOComponent component) {
        components.put(component.id(), component);
        log.debug("[SLOCascade] 注册组件: id={}, slo={}", component.id(), component.slo());
    }

    public void addDependency(String fromId, String toId) {
        dependencyGraph.computeIfAbsent(fromId, k -> new ArrayList<>()).add(toId);
    }

    public CascadeAnalysisResult analyzeFailure(String failedComponentId) {
        SLOComponent failed = components.get(failedComponentId);
        if (failed == null) {
            log.warn("[SLOCascade] 失败组件不存在: {}", failedComponentId);
            return new CascadeAnalysisResult(failedComponentId, List.of(), List.of(), "组件不存在");
        }

        List<String> affectedComponents = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        findAffected(failedComponentId, affectedComponents, visited);

        List<SLOComponent> affectedDetails = affectedComponents.stream()
                .map(components::get)
                .filter(Objects::nonNull)
                .toList();

        log.info("[SLOCascade] 级联分析: failed={}, affected={}",
                failedComponentId, affectedComponents.size());

        String summary = buildSummary(failed, affectedComponents);
        return new CascadeAnalysisResult(failedComponentId, affectedComponents,
                affectedDetails, summary);
    }

    private void findAffected(String componentId, List<String> affected, Set<String> visited) {
        if (!visited.add(componentId)) return;

        List<String> dependents = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : dependencyGraph.entrySet()) {
            if (entry.getValue().contains(componentId)) {
                dependents.add(entry.getKey());
            }
        }

        for (String dependent : dependents) {
            if (!affected.contains(dependent)) {
                affected.add(dependent);
            }
            findAffected(dependent, affected, visited);
        }
    }

    private String buildSummary(SLOComponent failed, List<String> affected) {
        if (affected.isEmpty()) {
            return String.format("组件 %s 失败，无下游受影响", failed.name());
        }
        return String.format("组件 %s 失败，级联影响 %d 个下游组件: %s",
                failed.name(), affected.size(), String.join(", ", affected));
    }

    public SLOComponent getComponent(String id) {
        return components.get(id);
    }

    public Collection<SLOComponent> getAllComponents() {
        return Collections.unmodifiableCollection(components.values());
    }

    public record SLOComponent(
            String id, String name,
            double slo, double currentSuccessRate,
            int tier
    ) {
        public boolean isBreached() {
            return currentSuccessRate < slo;
        }
    }

    public record CascadeAnalysisResult(
            String failedComponentId,
            List<String> affectedComponentIds,
            List<SLOComponent> affectedComponents,
            String summary
    ) {}
}