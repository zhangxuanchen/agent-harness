package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 四阶段门禁编排器。对应书中 Ch16 §16.1.1 —— CI/CD 流水线门禁系统。
 * <p>四阶段流水线：
 * <ol>
 *   <li><b>Eval（评估门禁）</b>：并行运行 100 个回归任务，对比基线/候选版本三指标退化</li>
 *   <li><b>Perf（性能门禁）</b>：从 OTel Span 提取 P95 延迟与 token 成本，对比基线</li>
 *   <li><b>Policy（策略门禁）</b>：安全规则 + 动态行为扫描，违规即阻断</li>
 *   <li><b>Human（人工门禁）</b>：高风险变更强制审批，低风险自动放行</li>
 * </ol>
 *
 * <p>教学主入口 {@link #executeGatePipeline} 顺序执行四门禁，任一 HARD_FAIL 立即短路
 * 返回 {@link GatePipelineState}；生产入口 {@link #executePipeline} 在其基础上产出
 * {@link PipelineExecution} 审计记录。这是汽车制造业质量门理念在软件交付中的直接
 * 应用——质量检查从最终环节前移到每个工序节点。
 */
@Service
public class GatePipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GatePipelineOrchestrator.class);

    private final EvalGate evalGate;
    private final PerfGate perfGate;
    private final PolicyGate policyGate;
    private final HumanGate humanGate;

    private final Map<String, PipelineExecution> executionHistory = new ConcurrentHashMap<>();

    public GatePipelineOrchestrator(EvalGate evalGate, PerfGate perfGate,
                                    PolicyGate policyGate, HumanGate humanGate) {
        this.evalGate = evalGate;
        this.perfGate = perfGate;
        this.policyGate = policyGate;
        this.humanGate = humanGate;
    }

    /**
     * 流水线阶段枚举（统一命名：Eval/Perf/Policy/Human）。
     */
    public enum PipelineStage {
        /** 评估门禁 */
        EVAL("评估门禁"),
        /** 性能门禁 */
        PERF("性能门禁"),
        /** 策略门禁 */
        POLICY("策略门禁"),
        /** 人工门禁 */
        HUMAN("人工门禁");

        private final String displayName;

        PipelineStage(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 流水线执行记录（生产审计）。
     * <p>对应书中 §16.1.1 "生产实现见 GatePipelineOrchestrator.PipelineExecution"。
     */
    public record PipelineExecution(
            String executionId,
            String agentVersionId,
            List<GateResult> results,
            PipelineStatus status,
            Instant startedAt,
            Instant completedAt
    ) {
        public enum PipelineStatus {
            RUNNING, PASSED, FAILED, ROLLED_BACK
        }
    }

    /**
     * 教学主入口：四阶段门禁顺序执行 + 短路阻断。
     * <p>对应书中 §16.1.1 {@code executeGatePipeline}。每道门禁独立判定，
     * 任一 HARD_FAIL 即阻断部署，避免缺陷向下游放大。
     *
     * @param baselineVersion  基线版本
     * @param candidateVersion 候选版本
     * @param riskLevel        变更风险等级（HIGH 触发人工审批，其余自动放行）
     * @return 门禁流水线状态（累积各门禁结果 + 阻断位置）
     */
    public GatePipelineState executeGatePipeline(
            String baselineVersion, String candidateVersion,
            RiskLevel riskLevel) {

        log.info("[GatePipeline] 开始门禁流水线: baseline={}, candidate={}, risk={}",
                baselineVersion, candidateVersion, riskLevel);

        List<GateResult> gates = new ArrayList<>();

        // Gate 1: 评估门禁（最耗时，并行运行 100 个回归任务）
        GateResult eval = evalGate.check(baselineVersion, candidateVersion);
        gates.add(eval);
        if (eval.isFailed()) {
            return GatePipelineState.blocked("Eval", gates); // 短路：评估退化 > 10%
        }

        // Gate 2: 性能门禁（利用评估运行中收集的性能数据）
        GateResult perf = perfGate.check(baselineVersion, candidateVersion);
        gates.add(perf);
        if (perf.isFailed()) {
            return GatePipelineState.blocked("Perf", gates); // 短路：性能退化超标
        }

        // Gate 3: 策略门禁（静态安全规则 + 动态行为扫描）
        GateResult policy = policyGate.check(candidateVersion);
        gates.add(policy);
        if (policy.isFailed()) {
            return GatePipelineState.blocked("Policy", gates); // 短路：发现安全违规
        }
        if (policy.status() == GateStatus.CONDITIONAL_FAIL) {
            notifyHumanReviewer(policy); // 告警但不阻断：策略临界违规
        }

        // Gate 4: 人工门禁（仅高风险变更触发审批；低风险自动放行）
        GateResult human;
        if (riskLevel == RiskLevel.HIGH) {
            String ticket = humanGate.requestApproval(candidateVersion, "高风险变更");
            human = humanGate.isApproved(ticket)
                    ? GateResult.pass("Human", 1.0)
                    : GateResult.fail("Human", "人工审批未通过: " + ticket);
        } else {
            human = GateResult.autoPass("Human"); // 低风险自动放行
        }
        gates.add(human);
        if (human.isFailed()) {
            return GatePipelineState.blocked("Human", gates); // 短路：人工审批不通过
        }

        log.info("[GatePipeline] 门禁全部通过: candidate={}", candidateVersion);
        return GatePipelineState.passed(gates); // 全部通过
    }

    /**
     * 生产入口：基于 Agent Bundle 执行门禁并产出审计记录。
     * <p>委托 {@link #executeGatePipeline} 执行四门禁，结果包装为
     * {@link PipelineExecution} 记入历史。
     *
     * @param bundle 待发布的 Agent Bundle 结构
     * @return 流水线执行记录
     */
    public PipelineExecution executePipeline(AgentBundle.AgentBundleStructure bundle) {
        String candidateVersion = bundle.metadata().versionId();
        // 基线版本：生产中从 AgentBundleRegistry 读取上一稳定版本
        String baselineVersion = "stable";
        RiskLevel riskLevel = RiskLevel.MEDIUM;

        Instant startedAt = Instant.now();
        GatePipelineState state = executeGatePipeline(baselineVersion, candidateVersion, riskLevel);
        Instant completedAt = Instant.now();

        PipelineExecution.PipelineStatus status = state.isBlocked()
                ? PipelineExecution.PipelineStatus.FAILED
                : PipelineExecution.PipelineStatus.PASSED;

        String executionId = "pipeline-" + UUID.randomUUID().toString().substring(0, 8);
        PipelineExecution execution = new PipelineExecution(
                executionId, candidateVersion, state.gates(), status, startedAt, completedAt);

        executionHistory.put(executionId, execution);
        log.info("[GatePipeline] 流水线完成: executionId={}, status={}, duration={}ms",
                executionId, status, completedAt.toEpochMilli() - startedAt.toEpochMilli());
        return execution;
    }

    /**
     * 获取执行历史。
     */
    public Collection<PipelineExecution> getExecutionHistory() {
        return Collections.unmodifiableCollection(executionHistory.values());
    }

    /**
     * 回滚指定版本。
     * <p>回滚动作由 AgentBundleRegistry.rollback() + TrafficRouter + CompensationExecutor 联合执行；
     * 本方法职责限于记录回滚事件并产生 {@link PipelineExecution} 审计记录。
     */
    public PipelineExecution rollback(String agentVersionId, String reason) {
        log.warn("[GatePipeline] 触发回滚: agentVersion={}, reason={}", agentVersionId, reason);

        String executionId = "rollback-" + UUID.randomUUID().toString().substring(0, 8);
        GateResult rollbackResult = GateResult.fail("Rollback", "回滚: " + reason);
        PipelineExecution rollback = new PipelineExecution(
                executionId, agentVersionId,
                List.of(rollbackResult),
                PipelineExecution.PipelineStatus.ROLLED_BACK,
                Instant.now(), Instant.now()
        );

        executionHistory.put(executionId, rollback);
        return rollback;
    }

    /** 策略临界违规告警：通知人工审核员但不阻断流水线。 */
    private void notifyHumanReviewer(GateResult policy) {
        log.warn("[GatePipeline] 策略临界违规告警（不阻断）: {}", policy.message());
    }
}
