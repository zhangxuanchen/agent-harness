package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CodePilot 进化追踪器。
 * <p>对应书中 Ch01 §1.5 —— 追踪 Agent Harness 的演进历史和版本变更。
 */
@Component
public class CodePilotEvolution {

    private static final Logger log = LoggerFactory.getLogger(CodePilotEvolution.class);

    private final List<EvolutionStep> evolutionHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> currentFeatures = new ConcurrentHashMap<>();

    /**
     * 进化步骤记录。
     */
    public record EvolutionStep(
            String version,
            String changeType,
            String description,
            Instant timestamp,
            Map<String, Object> metadata
    ) {}

    /**
     * 记录一个进化步骤。
     */
    public void recordStep(String version, String changeType, String description) {
        EvolutionStep step = new EvolutionStep(
                version, changeType, description, Instant.now(), Map.of()
        );
        evolutionHistory.add(step);
        log.info("[Evolution] 记录步骤: version={}, type={}, desc={}",
                version, changeType, description);
    }

    /**
     * 获取进化历史。
     */
    public List<EvolutionStep> getHistory() {
        return Collections.unmodifiableList(evolutionHistory);
    }

    /**
     * 注册当前特性。
     */
    public void registerFeature(String feature, String status) {
        currentFeatures.put(feature, status);
    }

    /**
     * 获取当前特性列表。
     */
    public Map<String, String> getCurrentFeatures() {
        return Collections.unmodifiableMap(currentFeatures);
    }
}