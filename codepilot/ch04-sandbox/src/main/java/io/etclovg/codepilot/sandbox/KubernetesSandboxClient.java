package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kubernetes 沙箱客户端——教学示意实现。
 * <p>对应书中 Ch04 §4.2.3 —— AgentScope 官方 {@code KubernetesSandboxClient} 的教学简化版。
 * <p>官方实现通过 Fabric8 Kubernetes Client 与真实 K8s API Server 通信，
 * 依次创建：① Namespace（多租户隔离） ② ResourceQuota（合计资源上限）
 * ③ NetworkPolicy（网络白名单） ④ ServiceAccount + RBAC（最小权限）
 * ⑤ PVC（团队工作目录） ⑥ Pod（执行代码）。
 * <p>本类为日志桩——打印等价的 K8s 资源创建动作，便于读者理解调用顺序，
 * 接入 Fabric8 后只需替换每一步的内部实现，Agent 业务代码零改动。
 */
@Component
public class KubernetesSandboxClient {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxClient.class);

    /** Pod 执行结果——对齐 DockerSandboxClient.ExecResult */
    public record K8sExecResult(
            boolean success,
            String output,
            int exitCode,
            long durationMs,
            String podName,
            String namespace
    ) {}

    /**
     * 为指定团队创建沙箱环境（教学示意：打印每个 K8s 资源）。
     * <p>等价于 AgentScope 官方的 6 步资源创建流程：
     * <ol>
     *   <li>Namespace：创建团队独立命名空间，资源名天然隔离</li>
     *   <li>ResourceQuota：该 Namespace 下所有 Pod 合计 CPU/内存/存储上限</li>
     *   <li>NetworkPolicy：只允许访问 allowEgressCidrs 中的 CIDR</li>
     *   <li>ServiceAccount + RoleBinding：绑定最小权限 Role</li>
     *   <li>PVC：挂载团队共享持久卷</li>
     *   <li>Pod：真正的执行容器，requests/limits 按 options 配置</li>
     * </ol>
     *
     * @param teamId  团队 ID（用于 Namespace 命名：agent-team-{teamId}）
     * @param options 配置对象
     */
    public void provisionTeamSandbox(String teamId, KubernetesSandboxClientOptions options) {
        String ns = "agent-team-" + teamId;
        var quota = options.getNamespaceQuota();
        log.info("[K8s-Sandbox] === 团队 {} 沙箱初始化，Namespace: {} ===", teamId, ns);
        log.info("[K8s-Sandbox] ① 创建 Namespace: {}", ns);
        log.info("[K8s-Sandbox] ② 创建 ResourceQuota: cpu={}核, memory={}MB, storage={}GB",
                quota.cpuLimitCores(), quota.memoryMbLimit(), quota.storageGbLimit());
        if (!options.getAllowedEgressCidrs().isEmpty()) {
            log.info("[K8s-Sandbox] ③ 创建 NetworkPolicy: 允许 CIDR={}, 端口={}",
                    options.getAllowedEgressCidrs(), options.getAllowedEgressPorts());
        } else {
            log.info("[K8s-Sandbox] ③ 创建 NetworkPolicy: 默认拒绝所有出站（断网模式）");
        }
        log.info("[K8s-Sandbox] ④ 创建 ServiceAccount/RBAC: sa={}, 绑定最小权限 Role",
                options.getServiceAccountName());
        if (options.getPersistentVolumeClaimName() != null) {
            log.info("[K8s-Sandbox] ⑤ 创建 PVC: {}, 挂载路径: {}",
                    options.getPersistentVolumeClaimName(), options.getWorkspaceMountPath());
        }
    }

    /**
     * 为单次 Agent 任务创建 Pod 并执行命令（教学示意）。
     *
     * @param teamId  团队 ID（决定 Pod 进哪个 Namespace）
     * @param options 配置（Pod CPU/内存请求、镜像、超时等）
     * @param command 要执行的命令
     * @return 执行结果
     */
    public K8sExecResult execInPod(String teamId, KubernetesSandboxClientOptions options, String command) {
        String ns = "agent-team-" + teamId;
        String podName = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        long start = System.currentTimeMillis();
        log.info("[K8s-Sandbox] 创建 Pod: ns={}, pod={}, image={}", ns, podName, options.getImage());
        log.info("[K8s-Sandbox]   Pod requests: cpu={}核, memory={}MB",
                options.getRequestCpuCores(), options.getRequestMemoryMb());
        log.info("[K8s-Sandbox]   Pod limits:   cpu={}核, memory={}MB",
                options.getLimitCpuCores(), options.getLimitMemoryMb());
        log.info("[K8s-Sandbox]   Pod labels:   {}", options.getPodLabels());
        log.info("[K8s-Sandbox]   SA:           {}", options.getServiceAccountName());
        log.info("[K8s-Sandbox] 执行命令: {}", command);
        long duration = System.currentTimeMillis() - start;
        return new K8sExecResult(true, "", 0, duration, podName, ns);
    }

    /**
     * 删除团队 Namespace 及所有关联资源（RBAC/NetworkPolicy/PVC/Pod 随 NS 删除一并清理）。
     */
    public void deprovisionTeamSandbox(String teamId) {
        String ns = "agent-team-" + teamId;
        log.info("[K8s-Sandbox] 删除团队 Namespace: {}（级联清理 Pod/PVC/Quota/NetworkPolicy）", ns);
    }

    /**
     * 读取 Pod 日志（用于 Agent 调试）。
     */
    public List<String> getPodLogs(String namespace, String podName, int tailLines) {
        return List.of("(teaching stub: tail " + tailLines + " lines of " + namespace + "/" + podName + ")");
    }
}
