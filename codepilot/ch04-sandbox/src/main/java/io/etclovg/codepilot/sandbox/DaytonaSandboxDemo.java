package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Daytona 实战演示——对应书中 §4.2.5 200 人公司多项目环境管理场景。
 * <p>展示一个完整流程：
 * <ol>
 *   <li>平台团队先完成 4 项**额外工作**：部署 Daytona Server（Docker Provider）→
 *       为 10 个项目各建 Snapshot（JDK8/17 + Node16/20 + 对应 DB）→
 *       配 CI 流水线（每次 JDK/依赖升级都重建快照并跑全量单测）→ 生成 API Key。</li>
 *   <li>来了一个"risk项目"的代码评审 Agent 任务 → 用 risk-project-v3 Snapshot 启 Workspace →
 *       git clone 风险项目代码 → 跑 mvn test → 输出 surefire-report。</li>
 *   <li>任务结束：stopWorkspace（保留磁盘，下次评审更快启动，CI 场景用）或 deleteWorkspace。</li>
 * </ol>
 */
@Component
public class DaytonaSandboxDemo {

    private static final Logger log = LoggerFactory.getLogger(DaytonaSandboxDemo.class);
    private final DaytonaSandboxClient daytona;

    public DaytonaSandboxDemo(DaytonaSandboxClient daytona) {
        this.daytona = daytona;
    }

    /**
     * §4.2.5 场景：风险团队的代码评审 Agent——每次评审都需要完全一致的
     * JDK 8 + Maven 3.8 + 公司私服配置 + risk-private-repo 只读凭证的环境。
     * 没有 Daytona 的话每个 Agent 容器都要 apt install + mvn install，耗时 5 分钟。
     * 用 Daytona 从 Snapshot 启动，10 秒进入可工作状态。
     */
    public void demonstrateCodeReviewForRiskProject(String apiKeyFromEnv) {
        // ================ 额外工作（Daytona 独有前置条件，4 步） ================
        // 1) Daytona Server 已部署: https://daytona.corp.com，Provider=Docker
        // 2) 平台团队已创建 Snapshot "risk-project-v3"：
        //      - JDK 1.8.0_392（和生产一致，避免 17→8 的源码兼容问题）
        //      - Maven 3.8.8 + settings.xml 指向公司 Nexus 私服
        //      - /workspace 预挂载 risk-private-repo 只读卷
        //      - 环境变量 RISK_DB_URL = jdbc:postgres://risk-db.corp.com:5432/risk
        // 3) CI 流水线：每次 Snapshot 更新（如 JDK 8u382 → 8u392），自动跑 2000 条 risk 项目单测，通过才发布 v4
        // 4) 已从 CLI 生成 Daytona API Key，通过环境变量 DAYTONA_API_KEY 注入

        DaytonaSandboxClientOptions opts = new DaytonaSandboxClientOptions()
            .serverUrl("https://daytona.corp.com")
            .apiKey(apiKeyFromEnv)
            .provider("docker")
            .snapshotId("risk-project-v3")            // 🎯 核心：从平台团队预制快照启动
            .workspaceRoot("/workspace")
            .timeoutSeconds(600)                       // mvn test 容易慢，拉长
            .idleStopSeconds(1800)                     // 空闲 30 分钟自动停（节省资源，保留磁盘）
            .allowGitRemote("https://git.corp.com/risk/credit-model.git")  // 只允许拉内网 Git
            .envVar("CODE_REVIEW_ID", "CR-2026-0811");

        String wsId = null;
        try {
            // ① 从快照创建 Workspace（~10s 进入可工作状态：JDK/Maven/Nexus 全都已经配好）
            wsId = daytona.createWorkspaceFromSnapshot(opts);
            daytona.startWorkspace(wsId);

            // ② clone 代码 → 跑 mvn test
            var clone = daytona.exec(wsId, opts,
                "git clone https://git.corp.com/risk/credit-model.git /workspace/credit-model && cd /workspace/credit-model");
            if (!clone.success()) log.warn("Git clone 失败: {}", clone.output());

            var test = daytona.exec(wsId, opts,
                "cd /workspace/credit-model && mvn test -Dtest=CreditRatingTest -pl credit-rating -am");
            log.info("[Demo] 单测结果：exitCode={}, 耗时={}ms, 快照版本={}",
                    test.exitCode(), test.durationMs(), test.usedSnapshotId());

            // ③ （可选）升级快照场景：如果 JDK 从 8u392 → 8u393，先手动在 Workspace 升级 JDK，
            //    跑 mvn test 全量通过，再在 CI 流水线里调用 createSnapshotFromWorkspace 得 risk-project-v4
            String newSnap = daytona.createSnapshotFromWorkspace(wsId, "risk-project-v4");
            log.info("[Demo] ⚠️  已在 CI 生成新快照 {}：请改 AgentScope snapshotId 后再上线", newSnap);
        } finally {
            // 代码评审任务结束：只 stop 不 delete，下次相同评审 3 秒 resume；CI 批量任务用 deleteWorkspace
            if (wsId != null) daytona.stopWorkspace(wsId);
        }
    }
}
