package io.etclovg.codepilot.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes 沙箱客户端配置。
 * <p>对应书中 Ch04 §4.2.3 —— K8s 后端的多租户隔离参数：Namespace、ResourceQuota、
 * NetworkPolicy、PVC 挂载、ServiceAccount 等。
 * <p>设计对齐 AgentScope 官方 {@code KubernetesSandboxClient} 的配置字段子集，
 * 同时支持 Spring {@code @ConfigurationProperties} 注入和 Builder 链式 API。
 * <p>多租户场景核心：每个业务团队一个独立 Namespace，通过 ResourceQuota 限 CPU/内存
 * 总量，通过 NetworkPolicy 只允许访问团队允许的 CIDR / 服务。
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.sandbox.kubernetes")
public class KubernetesSandboxClientOptions {

    /** K8s API Server 地址（本地开发用 https://localhost:6443，集群内走 ServiceAccount） */
    private String masterUrl = "https://kubernetes.default.svc";
    /** 默认 Namespace（多租户场景由 builder 按团队动态覆盖） */
    private String namespace = "agent-sandbox";
    /** 容器镜像 */
    private String image = "ubuntu:22.04";
    /** 容器内工作目录挂载路径 */
    private String workspaceMountPath = "/workspace";
    /** Pod 资源请求（requests）对应 CPU 核数 */
    private double requestCpuCores = 0.5;
    /** Pod 资源限制（limits）对应 CPU 核数 */
    private double limitCpuCores = 1.0;
    /** Pod 内存请求（MB） */
    private int requestMemoryMb = 256;
    /** Pod 内存限制（MB） */
    private int limitMemoryMb = 512;
    /** 单步执行超时（秒） */
    private int timeoutSeconds = 60;
    /** ImagePullSecret 名称（私有仓库拉镜像时需要） */
    private String imagePullSecret;
    /** ServiceAccount 名称——绑定 RBAC 权限，确保 Pod 权限最小化 */
    private String serviceAccountName = "agent-sandbox-sa";
    /** Pod 标签（用于审计/筛选/成本分摊） */
    private Map<String, String> podLabels = new HashMap<>();
    /** NetworkPolicy 允许的出站 CIDR 列表（默认空 = 全部拒绝，即断网） */
    private List<String> allowedEgressCidrs = new ArrayList<>();
    /** NetworkPolicy 允许的出站端口列表 */
    private List<Integer> allowedEgressPorts = new ArrayList<>();
    /** ResourceQuota——该 Namespace 下所有 Agent 任务合计资源上限 */
    private ResourceQuotaSpec namespaceQuota = new ResourceQuotaSpec(50.0, 100 * 1024, 1000L);
    /** PVC（持久卷声明）名称——用于挂载团队共享的工作目录 */
    private String persistentVolumeClaimName;

    // ===== JavaBean getters/setters（Spring 注入用） =====
    public String getMasterUrl() { return masterUrl; }
    public void setMasterUrl(String v) { this.masterUrl = v; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String v) { this.namespace = v; }
    public String getImage() { return image; }
    public void setImage(String v) { this.image = v; }
    public String getWorkspaceMountPath() { return workspaceMountPath; }
    public void setWorkspaceMountPath(String v) { this.workspaceMountPath = v; }
    public double getRequestCpuCores() { return requestCpuCores; }
    public void setRequestCpuCores(double v) { this.requestCpuCores = v; }
    public double getLimitCpuCores() { return limitCpuCores; }
    public void setLimitCpuCores(double v) { this.limitCpuCores = v; }
    public int getRequestMemoryMb() { return requestMemoryMb; }
    public void setRequestMemoryMb(int v) { this.requestMemoryMb = v; }
    public int getLimitMemoryMb() { return limitMemoryMb; }
    public void setLimitMemoryMb(int v) { this.limitMemoryMb = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    public String getImagePullSecret() { return imagePullSecret; }
    public void setImagePullSecret(String v) { this.imagePullSecret = v; }
    public String getServiceAccountName() { return serviceAccountName; }
    public void setServiceAccountName(String v) { this.serviceAccountName = v; }
    public Map<String, String> getPodLabels() { return podLabels; }
    public void setPodLabels(Map<String, String> v) { this.podLabels = v; }
    public List<String> getAllowedEgressCidrs() { return allowedEgressCidrs; }
    public void setAllowedEgressCidrs(List<String> v) { this.allowedEgressCidrs = v; }
    public List<Integer> getAllowedEgressPorts() { return allowedEgressPorts; }
    public void setAllowedEgressPorts(List<Integer> v) { this.allowedEgressPorts = v; }
    public ResourceQuotaSpec getNamespaceQuota() { return namespaceQuota; }
    public void setNamespaceQuota(ResourceQuotaSpec v) { this.namespaceQuota = v; }
    public String getPersistentVolumeClaimName() { return persistentVolumeClaimName; }
    public void setPersistentVolumeClaimName(String v) { this.persistentVolumeClaimName = v; }

    // ===== Fluent Builder 方法（链式 API，书中示例用） =====
    public KubernetesSandboxClientOptions namespace(String namespace) {
        this.namespace = namespace; return this;
    }
    public KubernetesSandboxClientOptions image(String image) {
        this.image = image; return this;
    }
    public KubernetesSandboxClientOptions requestCpu(double cores) {
        this.requestCpuCores = cores; return this;
    }
    public KubernetesSandboxClientOptions limitCpu(double cores) {
        this.limitCpuCores = cores; return this;
    }
    public KubernetesSandboxClientOptions requestMemoryMb(int mb) {
        this.requestMemoryMb = mb; return this;
    }
    public KubernetesSandboxClientOptions limitMemoryMb(int mb) {
        this.limitMemoryMb = mb; return this;
    }
    public KubernetesSandboxClientOptions timeoutSeconds(int s) {
        this.timeoutSeconds = s; return this;
    }
    public KubernetesSandboxClientOptions serviceAccountName(String sa) {
        this.serviceAccountName = sa; return this;
    }
    /** 追加允许的出站 CIDR（NetworkPolicy） */
    public KubernetesSandboxClientOptions allowEgressCidr(String cidr, int... ports) {
        this.allowedEgressCidrs.add(cidr);
        for (int p : ports) this.allowedEgressPorts.add(p);
        return this;
    }
    public KubernetesSandboxClientOptions podLabel(String k, String v) {
        this.podLabels.put(k, v); return this;
    }
    public KubernetesSandboxClientOptions persistentVolumeClaim(String pvc) {
        this.persistentVolumeClaimName = pvc; return this;
    }
    public KubernetesSandboxClientOptions namespaceQuota(double cpuLimit, int memoryMbLimit, long pvcStorageGb) {
        this.namespaceQuota = new ResourceQuotaSpec(cpuLimit, memoryMbLimit, pvcStorageGb);
        return this;
    }

    /**
     * Namespace 级 ResourceQuota 规格——该团队所有 Agent Pod 合计资源上限。
     * <p>对齐 K8s 官方 {@code ResourceQuota} 字段子集。
     *
     * @param cpuLimitCores    CPU 限制（核，所有 Pod limits.cpu 之和不得超过）
     * @param memoryMbLimit    内存限制（MB，所有 Pod limits.memory 之和不得超过）
     * @param storageGbLimit   PVC 存储合计上限（GB）
     */
    public record ResourceQuotaSpec(
            double cpuLimitCores,
            int memoryMbLimit,
            long storageGbLimit
    ) {}
}
