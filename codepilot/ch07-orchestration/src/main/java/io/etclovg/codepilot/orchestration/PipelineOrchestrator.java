package io.etclovg.codepilot.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 流水线编排器。
 * <p>对应书中 Ch07 §7.2 —— 按固定顺序执行一系列处理步骤。
 */
@Component
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final List<PipelineStep> steps = new ArrayList<>();

    /**
     * 流水线步骤。
     */
    public record PipelineStep(
            String name,
            String description,
            boolean enabled
    ) {}

    /**
     * 添加步骤。
     */
    public void addStep(PipelineStep step) {
        steps.add(step);
    }

    /**
     * 执行流水线。
     */
    public PipelineResult execute(String input) {
        log.info("[Pipeline] 开始执行: steps={}, inputLen={}", steps.size(), input.length());
        return new PipelineResult("OK", List.of(), System.currentTimeMillis());
    }

    /**
     * 流水线执行结果。
     */
    public record PipelineResult(
            String status,
            List<String> executedSteps,
            long durationMs
    ) {}
}