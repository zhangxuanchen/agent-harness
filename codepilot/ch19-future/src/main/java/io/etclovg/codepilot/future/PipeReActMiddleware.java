package io.etclovg.codepilot.future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Pipe ReAct 中间件：管道式 ReAct 推理增强
 * 对应书中 Ch19 —— 未来展望：下一代 Agent 推理范式
 */
@Component
public class PipeReActMiddleware {

    private static final Logger log = LoggerFactory.getLogger(PipeReActMiddleware.class);

    private final List<PipeStage> stages = new ArrayList<>();
    private final PipeConfig config;

    public PipeReActMiddleware() {
        this(new PipeConfig(3, 10, true));
        registerDefaultStages();
    }

    public PipeReActMiddleware(PipeConfig config) {
        this.config = config;
        registerDefaultStages();
    }

    public PipeResult executePipeline(String task, String context) {
        log.info("[PipeReActMiddleware] 开始管道执行: task={}, stages={}", task, stages.size());

        String currentInput = task;
        List<StageResult> stageResults = new ArrayList<>();
        String finalOutput = null;
        boolean completed = false;

        for (int i = 0; i < stages.size() && i < config.maxStages(); i++) {
            PipeStage stage = stages.get(i);
            log.debug("[PipeReActMiddleware] 执行阶段 {}: {}", i, stage.name());

            StageResult result = stage.execute(currentInput, context);
            stageResults.add(result);

            if (result.error() != null) {
                log.warn("[PipeReActMiddleware] 阶段失败: stage={}, error={}",
                        stage.name(), result.error());
                return new PipeResult(task, false, null, result.error(),
                        stageResults, i, Instant.now());
            }

            currentInput = result.output();
            finalOutput = result.output();

            if (result.completed()) {
                completed = true;
                log.info("[PipeReActMiddleware] 管道提前完成于阶段 {}", i);
                break;
            }
        }

        boolean success = completed || finalOutput != null;
        log.info("[PipeReActMiddleware] 管道执行完成: success={}, stagesExecuted={}",
                success, stageResults.size());

        return new PipeResult(task, success, finalOutput, null,
                stageResults, stageResults.size(), Instant.now());
    }

    public void addStage(PipeStage stage) {
        stages.add(stage);
        log.info("[PipeReActMiddleware] 添加阶段: name={}", stage.name());
    }

    public void removeStage(String stageName) {
        stages.removeIf(s -> s.name().equals(stageName));
    }

    public List<PipeStage> getStages() {
        return Collections.unmodifiableList(stages);
    }

    public PipeConfig getConfig() {
        return config;
    }

    private void registerDefaultStages() {
        stages.add(new AnalyzeStage());
        stages.add(new PlanStage());
        stages.add(new ExecuteStage());
        stages.add(new VerifyStage());
    }

    public interface PipeStage {
        String name();
        StageResult execute(String input, String context);
    }

    private static class AnalyzeStage implements PipeStage {
        @Override
        public String name() { return "analyze"; }

        @Override
        public StageResult execute(String input, String context) {
            String analysis = "分析任务: " + input + "\n关键要素: 已识别";
            return new StageResult("analyze", analysis, null, false);
        }
    }

    private static class PlanStage implements PipeStage {
        @Override
        public String name() { return "plan"; }

        @Override
        public StageResult execute(String input, String context) {
            String plan = "制定执行计划:\n1. 理解需求\n2. 收集信息\n3. 执行操作\n4. 验证结果";
            return new StageResult("plan", plan, null, false);
        }
    }

    private static class ExecuteStage implements PipeStage {
        @Override
        public String name() { return "execute"; }

        @Override
        public StageResult execute(String input, String context) {
            String execution = "执行中... 模拟完成";
            return new StageResult("execute", execution, null, false);
        }
    }

    private static class VerifyStage implements PipeStage {
        @Override
        public String name() { return "verify"; }

        @Override
        public StageResult execute(String input, String context) {
            String verification = "验证通过: 输出符合预期";
            return new StageResult("verify", verification, null, true);
        }
    }

    public record PipeConfig(
            int maxStages, int maxIterationsPerStage, boolean parallelEnabled
    ) {}

    public record StageResult(
            String stageName, String output, String error, boolean completed
    ) {}

    public record PipeResult(
            String task, boolean success, String output, String error,
            List<StageResult> stages, int stagesExecuted, Instant timestamp
    ) {}
}