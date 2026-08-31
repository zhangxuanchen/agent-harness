package io.etclovg.codepilot.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ConsistencyVerifier {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyVerifier.class);

    private final Map<Phase, PhaseFunction> phaseFunctions;
    private final List<Phase> phaseOrder;

    private ConsistencyVerifier(ConsistencyVerifierBuilder builder) {
        this.phaseFunctions = new LinkedHashMap<>(builder.phaseFunctions);
        this.phaseOrder = new ArrayList<>(builder.phaseOrder);
    }

    public static ConsistencyVerifierBuilder builder() {
        return new ConsistencyVerifierBuilder();
    }

    public PhaseResult verifyAll(AgentOutputList results) {
        for (Phase phase : phaseOrder) {
            PhaseFunction func = phaseFunctions.get(phase);
            if (func == null) continue;
            PhaseResult pr = func.apply(results);
            if (pr.status() == PhaseResult.Status.PASS) {
                log.debug("Phase {} passed", phase);
            } else if (pr.status() == PhaseResult.Status.ADVANCE_TO_NEXT) {
                log.debug("Phase {} advanced to next", phase);
            } else if (pr.status() == PhaseResult.Status.RESOLVED) {
                log.info("Phase {} resolved with result", phase);
                return pr;
            }
        }
        return PhaseResult.ADVANCE_TO_NEXT();
    }

    public enum Phase {
        SCHEMA,
        CROSS_VALIDATION,
        JUDGE,
        MAJORITY_VOTE
    }

    public static class PhaseResult {
        public enum Status {
            PASS,
            ADVANCE_TO_NEXT,
            RESOLVED
        }

        private final Status status;
        private final Object resolvedResult;

        private PhaseResult(Status status, Object resolvedResult) {
            this.status = status;
            this.resolvedResult = resolvedResult;
        }

        public static PhaseResult PASS() {
            return new PhaseResult(Status.PASS, null);
        }

        public static PhaseResult ADVANCE_TO_NEXT() {
            return new PhaseResult(Status.ADVANCE_TO_NEXT, null);
        }

        public static PhaseResult resolve(Object result) {
            return new PhaseResult(Status.RESOLVED, result);
        }

        public Status status() {
            return status;
        }

        public Optional<Object> resolvedResult() {
            return Optional.ofNullable(resolvedResult);
        }
    }

    @FunctionalInterface
    public interface PhaseFunction {
        PhaseResult apply(AgentOutputList results);
    }

    public static class AgentOutputList extends ArrayList<AgentOutput> {
        public AgentOutputList() {
            super();
        }

        public AgentOutputList(Collection<? extends AgentOutput> c) {
            super(c);
        }

        public AgentOutput majorityVote(double ratio) {
            if (isEmpty()) return null;
            int threshold = (int) Math.ceil(size() * ratio);

            Map<AgentOutput, Integer> counts = new IdentityHashMap<>();
            Map<String, List<AgentOutput>> agreementGroups = new LinkedHashMap<>();

            for (AgentOutput o : this) {
                boolean matched = false;
                for (Map.Entry<String, List<AgentOutput>> entry : agreementGroups.entrySet()) {
                    AgentOutput representative = entry.getValue().get(0);
                    if (o.agreesWith(representative)) {
                        entry.getValue().add(o);
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    String key = "group_" + agreementGroups.size();
                    List<AgentOutput> newGroup = new ArrayList<>();
                    newGroup.add(o);
                    agreementGroups.put(key, newGroup);
                }
            }

            AgentOutput best = null;
            int bestCount = 0;
            for (List<AgentOutput> group : agreementGroups.values()) {
                if (group.size() > bestCount) {
                    bestCount = group.size();
                    best = group.get(0);
                }
            }

            if (bestCount >= threshold) {
                return best;
            }
            return best;
        }
    }

    public static class AgentOutput {
        private final Object payload;

        public AgentOutput(Object payload) {
            this.payload = payload;
        }

        public Object payload() {
            return payload;
        }

        public boolean matches(SchemaValidator sv) {
            return sv.matches(this);
        }

        public boolean agreesWith(AgentOutput other) {
            if (other == null) return false;
            if (this == other) return true;
            Object p1 = this.payload;
            Object p2 = other.payload;
            if (p1 == null && p2 == null) return true;
            if (p1 == null || p2 == null) return false;
            if (p1.equals(p2)) return true;
            String s1 = String.valueOf(p1);
            String s2 = String.valueOf(p2);
            if (s1.equals(s2)) return true;
            int common = 0;
            int total = Math.max(s1.length(), s2.length());
            Set<Character> set1 = new HashSet<>();
            for (char c : s1.toCharArray()) set1.add(c);
            for (char c : s2.toCharArray()) if (set1.contains(c)) common++;
            if (total == 0) return true;
            return (double) common / total >= 0.7;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AgentOutput that)) return false;
            return Objects.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(payload);
        }
    }

    public static class ConsistencyVerifierBuilder {
        private final Map<Phase, PhaseFunction> phaseFunctions = new LinkedHashMap<>();
        private final List<Phase> phaseOrder = new ArrayList<>();

        public ConsistencyVerifierBuilder phase(Phase phase, PhaseFunction func) {
            phaseFunctions.put(phase, func);
            if (!phaseOrder.contains(phase)) {
                phaseOrder.add(phase);
            }
            return this;
        }

        public ConsistencyVerifier build() {
            if (phaseOrder.isEmpty()) {
                phase(Phase.SCHEMA, results -> {
                    boolean allMatch = true;
                    for (AgentOutput o : results) {
                        if (!o.matches(new SchemaValidator())) {
                            allMatch = false;
                            break;
                        }
                    }
                    return allMatch ? PhaseResult.PASS() : PhaseResult.ADVANCE_TO_NEXT();
                });
                phase(Phase.CROSS_VALIDATION, results -> PhaseResult.ADVANCE_TO_NEXT());
                phase(Phase.JUDGE, results -> {
                    LlmJudge judge = new LlmJudge();
                    AgentOutput best = judge.selectBest(results);
                    return best != null ? PhaseResult.resolve(best) : PhaseResult.ADVANCE_TO_NEXT();
                });
                phase(Phase.MAJORITY_VOTE, results -> {
                    AgentOutput majority = results.majorityVote(0.6);
                    return majority != null ? PhaseResult.resolve(majority) : PhaseResult.ADVANCE_TO_NEXT();
                });
            }
            return new ConsistencyVerifier(this);
        }
    }
}
