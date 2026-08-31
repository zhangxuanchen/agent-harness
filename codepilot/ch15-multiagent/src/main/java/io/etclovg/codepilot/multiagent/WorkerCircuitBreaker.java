package io.etclovg.codepilot.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public class WorkerCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(WorkerCircuitBreaker.class);

    private final int failureThreshold;
    private final int slidingWindowSize;
    private final Duration timeout;
    private final int halfOpenMaxRequests;
    private final Duration cooldownPeriod;
    private final SnapshotStrategy snapshotStrategy;
    private final UltimateFallback ultimateFallback;

    private final Map<String, BreakerInternalState> breakerStates = new ConcurrentHashMap<>();

    private WorkerCircuitBreaker(WorkerCircuitBreakerBuilder builder) {
        this.failureThreshold = builder.failureThreshold;
        this.slidingWindowSize = builder.slidingWindowSize;
        this.timeout = builder.timeout;
        this.halfOpenMaxRequests = builder.halfOpenMaxRequests;
        this.cooldownPeriod = builder.cooldownPeriod;
        this.snapshotStrategy = builder.snapshotStrategy;
        this.ultimateFallback = builder.ultimateFallback;
    }

    public static WorkerCircuitBreakerBuilder builder() {
        return new WorkerCircuitBreakerBuilder();
    }

    public Optional<FallbackResult> executeOrFallback(TaskSpec task, Function<TaskSpec, WorkerTaskResult> executor) {
        BreakerInternalState state = getOrCreateState(task.taskId());

        if (!canExecute(state)) {
            log.warn("[CB] Circuit OPEN for task {}, triggering ultimate fallback", task.taskId());
            return Optional.ofNullable(ultimateFallback != null ? ultimateFallback.fallback(task) : null);
        }

        ContextSnapshot.Snapshot snapshot = null;
        if (snapshotStrategy != null && snapshotStrategy.getDepsExtractor() != null) {
            List<String> deps = snapshotStrategy.getDepsExtractor().apply(new WorkerTaskResult(List.of(), null));
            snapshot = new ContextSnapshot.Snapshot(task.taskId(), deps);
        }

        try {
            WorkerTaskResult result = executor.apply(task);
            recordSuccess(state);
            return Optional.empty();
        } catch (Exception e) {
            recordFailure(state);
            if (snapshotStrategy != null && snapshotStrategy.getFailureHandler() != null && snapshot != null) {
                snapshotStrategy.getFailureHandler().accept(snapshot);
            }
            if (isCircuitOpen(state)) {
                log.warn("[CB] Task {} failed, circuit OPEN, falling back", task.taskId());
                return Optional.ofNullable(ultimateFallback != null ? ultimateFallback.fallback(task) : null);
            }
            log.warn("[CB] Task {} failed: {}", task.taskId(), e.getMessage());
            return Optional.empty();
        }
    }

    private BreakerInternalState getOrCreateState(String key) {
        return breakerStates.computeIfAbsent(key, k -> new BreakerInternalState());
    }

    private boolean canExecute(BreakerInternalState state) {
        long now = System.currentTimeMillis();
        switch (state.state.get()) {
            case CLOSED:
                return true;
            case OPEN:
                if (now - state.lastFailureTime.get() >= cooldownPeriod.toMillis()) {
                    state.state.set(State.HALF_OPEN);
                    state.halfOpenRequests.set(0);
                    return true;
                }
                return false;
            case HALF_OPEN:
                return state.halfOpenRequests.incrementAndGet() <= halfOpenMaxRequests;
            default:
                return false;
        }
    }

    private boolean isCircuitOpen(BreakerInternalState state) {
        return state.state.get() == State.OPEN;
    }

    private void recordSuccess(BreakerInternalState state) {
        state.failureCount.set(0);
        if (state.state.get() == State.HALF_OPEN) {
            state.state.set(State.CLOSED);
            state.halfOpenRequests.set(0);
            log.info("[CB] Circuit CLOSED again");
        }
    }

    private void recordFailure(BreakerInternalState state) {
        long now = System.currentTimeMillis();
        state.lastFailureTime.set(now);
        state.failureHistory.add(now);
        while (state.failureHistory.size() > slidingWindowSize) {
            state.failureHistory.poll();
        }
        int currentFailures = state.failureCount.incrementAndGet();
        if (currentFailures >= failureThreshold) {
            state.state.set(State.OPEN);
            log.warn("[CB] Circuit OPEN: failures={}/{}", currentFailures, failureThreshold);
        }
    }

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private static class BreakerInternalState {
        final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicLong lastFailureTime = new AtomicLong(0);
        final AtomicInteger halfOpenRequests = new AtomicInteger(0);
        final Deque<Long> failureHistory = new ArrayDeque<>();
    }

    public interface SnapshotStrategy {
        SnapshotStrategy captureBefore(Function<WorkerTaskResult, List<String>> depsExtractor);
        SnapshotStrategy restoreOnFailure(Consumer<ContextSnapshot.Snapshot> handler);
        Function<WorkerTaskResult, List<String>> getDepsExtractor();
        Consumer<ContextSnapshot.Snapshot> getFailureHandler();
    }

    public static class DefaultSnapshotStrategy implements SnapshotStrategy {
        private Function<WorkerTaskResult, List<String>> depsExtractor;
        private Consumer<ContextSnapshot.Snapshot> failureHandler;

        @Override
        public SnapshotStrategy captureBefore(Function<WorkerTaskResult, List<String>> depsExtractor) {
            this.depsExtractor = depsExtractor;
            return this;
        }

        @Override
        public SnapshotStrategy restoreOnFailure(Consumer<ContextSnapshot.Snapshot> handler) {
            this.failureHandler = handler;
            return this;
        }

        @Override
        public Function<WorkerTaskResult, List<String>> getDepsExtractor() {
            return depsExtractor;
        }

        @Override
        public Consumer<ContextSnapshot.Snapshot> getFailureHandler() {
            return failureHandler;
        }
    }

    @FunctionalInterface
    public interface UltimateFallback {
        FallbackResult fallback(TaskSpec task);
    }

    public record FallbackResult(boolean partial, String message, int completedSteps, int totalSteps) {
        public static FallbackResult partial(String message, int completedSteps, int totalSteps) {
            return new FallbackResult(true, message, completedSteps, totalSteps);
        }

        public static FallbackResult full(String message) {
            return new FallbackResult(false, message, 0, 0);
        }
    }

    public record TaskSpec(String taskId, int completedSteps, int totalSteps) {}

    public record WorkerTaskResult(List<String> dependencies, Object payload) {}

    public static class WorkerCircuitBreakerBuilder {
        private int failureThreshold = 5;
        private int slidingWindowSize = 20;
        private Duration timeout = Duration.ofSeconds(30);
        private int halfOpenMaxRequests = 3;
        private Duration cooldownPeriod = Duration.ofSeconds(10);
        private SnapshotStrategy snapshotStrategy;
        private UltimateFallback ultimateFallback;

        public WorkerCircuitBreakerBuilder failureThreshold(int threshold) {
            this.failureThreshold = threshold;
            return this;
        }

        public WorkerCircuitBreakerBuilder slidingWindowSize(int size) {
            this.slidingWindowSize = size;
            return this;
        }

        public WorkerCircuitBreakerBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public WorkerCircuitBreakerBuilder halfOpenMaxRequests(int limit) {
            this.halfOpenMaxRequests = limit;
            return this;
        }

        public WorkerCircuitBreakerBuilder cooldownPeriod(Duration period) {
            this.cooldownPeriod = period;
            return this;
        }

        public WorkerCircuitBreakerBuilder snapshotStrategy(SnapshotStrategy strategy) {
            this.snapshotStrategy = strategy;
            return this;
        }

        public WorkerCircuitBreakerBuilder ultimateFallback(UltimateFallback fallback) {
            this.ultimateFallback = fallback;
            return this;
        }

        public WorkerCircuitBreaker build() {
            return new WorkerCircuitBreaker(this);
        }
    }
}
