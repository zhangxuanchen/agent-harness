package io.etclovg.codepilot.multiagent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class ContextSnapshot implements WorkerCircuitBreaker.SnapshotStrategy {

    private Function<WorkerCircuitBreaker.WorkerTaskResult, List<String>> depsExtractor;
    private Consumer<Snapshot> failureHandler;

    @Override
    public ContextSnapshot captureBefore(Function<WorkerCircuitBreaker.WorkerTaskResult, List<String>> depsExtractor) {
        this.depsExtractor = depsExtractor;
        return this;
    }

    @Override
    public ContextSnapshot restoreOnFailure(Consumer<Snapshot> handler) {
        this.failureHandler = handler;
        return this;
    }

    @Override
    public Function<WorkerCircuitBreaker.WorkerTaskResult, List<String>> getDepsExtractor() {
        return depsExtractor;
    }

    @Override
    public Consumer<Snapshot> getFailureHandler() {
        return failureHandler;
    }

    public record Snapshot(String taskId, List<String> dependencies) {}
}
