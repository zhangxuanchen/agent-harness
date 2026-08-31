package io.etclovg.codepilot.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pipe + ReAct 编排器。
 * <p>对应书中 Ch07 §7.3 —— 管道和 ReAct 混合编排模式。
 */
@Component
public class PipeReActOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipeReActOrchestrator.class);
    private final PipelineOrchestrator pipeline;
    private final ReActOrchestrator reactOrchestrator;

    public PipeReActOrchestrator(PipelineOrchestrator pipeline, ReActOrchestrator reactOrchestrator) {
        this.pipeline = pipeline;
        this.reactOrchestrator = reactOrchestrator;
    }

    /**
     * 执行 Pipe + ReAct 编排。
     */
    public OrchestrationResult execute(String task) {
        log.info("[PipeReAct] 开始编排: task={}", task);
        return new OrchestrationResult("COMPLETED", "", 0);
    }

    /**
     * 编排结果。
     */
    public record OrchestrationResult(
            String status,
            String output,
            long durationMs
    ) {}
}