package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * K8s 多租户沙箱实战演示——对应书中 §4.2.3 银行 AI 平台场景。
 * <p>展示一个完整流程：
 * <ol>
 *   <li>平台团队初始化（为 20 个业务团队批量 provision 独立 Namespace + Quota + NetworkPolicy）</li>
 *   <li>团队 A 来了一个"客户贷款数据分析"的 Agent 任务 → 建 Pod 执行 Python 脚本</li>
 *   <li>任务结束 → Pod 资源立即释放，Namespace 保留以便下次任务</li>
 * </ol>
 * <p>演示构造器注入 {@link KubernetesSandboxClient}，与 AgentScope 官方 DI 方式对齐。
 */
@Component
public class KubernetesSandboxDemo {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxDemo.class);

    private final KubernetesSandboxClient k8s;

    public KubernetesSandboxDemo(KubernetesSandboxClient k8s) {
        this.k8s = k8s;
    }

    /**
     * 运行 §4.2.3 银行场景演示。
     */
    public void demonstrateBankMultiTenant() {
        // ============= 步骤 1：平台初始化——20 个业务团队各自独立 Namespace =============
        // 每个团队的 NetworkPolicy 只允许访问自己部门的 DB CIDR，禁止跨 Namespace 通信
        String[] teams = {"risk", "credit", "wealth", "retail", "corp"}; // 演示 5 个，实际 20 个
        for (String team : teams) {
            KubernetesSandboxClientOptions teamOpts = buildTeamOptions(team);
            k8s.provisionTeamSandbox(team, teamOpts);
        }

        // ============= 步骤 2：团队 A(credit) 来了一个 Agent 任务 =============
        // 任务：在风险团队的数据沙箱里跑"客户贷款数据分析"
        //   - Pod 挂 credit-team-pvc（团队共享数据卷，只读团队数据湖目录）
        //   - SA credit-analyst-sa 只有 DB 只读权限（RBAC 预先绑定）
        //   - NetworkPolicy 只允许访问 10.20.0.0/16（credit 部门 DB CIDR）的 5432 端口
        String team = "credit";
        KubernetesSandboxClientOptions taskOpts = buildTeamOptions(team)
                .requestCpu(2.0).limitCpu(4.0)
                .requestMemoryMb(2048).limitMemoryMb(4096)
                .timeoutSeconds(300)
                .podLabel("task-type", "loan-analysis")
                .podLabel("triggered-by", "agent-job-78231");
        String command = "python /workspace/analyze_loan_defaults.py --dataset 2026Q2.parquet --output report.csv";

        log.info("[Demo] 团队 {} 提交任务: {}", team, command);
        var result = k8s.execInPod(team, taskOpts, command);
        log.info("[Demo] 任务完成：pod={}/{}, 耗时={}ms, 退出码={}",
                result.namespace(), result.podName(), result.durationMs(), result.exitCode());

        // ============= 步骤 3：读取日志调试（AgentScope 官方 API 对应 sandbox.logs()） =============
        var logs = k8s.getPodLogs(result.namespace(), result.podName(), 20);
        log.info("[Demo] Pod 最后 20 行日志：{}", logs);
    }

    /**
     * 根据团队 ID 构造该团队默认的多租户隔离配置。
     * <p>实际生产中通常从配置中心读取，此处用 switch 硬编码演示差异：
     * 每个团队独立 PVC、独立 SA、独立 DB CIDR 白名单。
     */
    private KubernetesSandboxClientOptions buildTeamOptions(String team) {
        KubernetesSandboxClientOptions opts = new KubernetesSandboxClientOptions()
                .namespace("agent-team-" + team)
                .image("code-pilot:agent-runner-v1")
                .serviceAccountName(team + "-analyst-sa")
                .persistentVolumeClaim(team + "-team-pvc");
        // 每个团队的 DB 网段不同——演示 NetworkPolicy 的租户差异化配置
        switch (team) {
            case "risk"   -> opts.allowEgressCidr("10.10.0.0/16", 5432);
            case "credit" -> opts.allowEgressCidr("10.20.0.0/16", 5432);
            case "wealth" -> opts.allowEgressCidr("10.30.0.0/16", 1433);
            default       -> {} // 其他团队默认断网（NetworkPolicy 拒绝所有出站）
        }
        // Namespace 合计配额：CPU 50 核 / 内存 100GB / PVC 500GB
        opts.namespaceQuota(50.0, 100 * 1024, 500L);
        return opts;
    }
}
