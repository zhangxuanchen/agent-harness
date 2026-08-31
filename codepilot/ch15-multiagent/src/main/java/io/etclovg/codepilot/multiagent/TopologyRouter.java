package io.etclovg.codepilot.multiagent;

import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 拓扑路由器。对应书中 Ch15 §15.4.1。
 * 根据 DagMetrics 的 couplingDensity gamma 选择调度拓扑：
 * gamma < 0.3 → PARALLEL，0.3 ≤ gamma ≤ 0.7 → HIERARCHICAL，gamma > 0.7 → SEQUENTIAL
 */
@Component
public class TopologyRouter {
    public DagDecomposer.Topology route(DagDecomposer.DagMetrics metrics) {
        if (metrics.gamma() < 0.3) return DagDecomposer.Topology.PARALLEL;
        if (metrics.gamma() <= 0.7) return DagDecomposer.Topology.HIERARCHICAL;
        return DagDecomposer.Topology.SEQUENTIAL;
    }
    public List<String> schedule(DagDecomposer.Dag dag, DagDecomposer.Topology topology) { return dag.topologicalOrder(); }
}
