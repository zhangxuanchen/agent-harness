package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 沙箱池管理器。
 * <p>对应书中 Ch04 §4.4.1 —— 沙箱预热池与生命周期管理。
 * <p>管理沙箱实例的创建、分配、复用、回收和销毁。
 * <p>支持按 Profile 分池管理，每个 Profile 有独立的容量限制。
 */
@Component
public class SandboxPool {

    private static final Logger log = LoggerFactory.getLogger(SandboxPool.class);

    /**
     * 沙箱实例状态。
     */
    public enum SandboxState {
        /** 空闲，可分配 */
        IDLE,
        /** 正在使用 */
        IN_USE,
        /** 初始化中 */
        INITIALIZING,
        /** 销毁中 */
        DESTROYED,
        /** 异常状态 */
        ERROR
    }

    /**
     * 沙箱实例数据模型。
     */
    public record SandboxInstance(
            String id,
            SandboxProfile profile,
            SandboxState state,
            Instant createdAt,
            Instant lastUsedAt,
            String currentTaskId,
            int useCount,
            long totalUsageMs
    ) {
        public boolean isExpired(long maxIdleMs) {
            if (state != SandboxState.IDLE) return false;
            Instant reference = lastUsedAt != null ? lastUsedAt : createdAt;
            return Instant.now().toEpochMilli() - reference.toEpochMilli() > maxIdleMs;
        }
    }

    /**
     * 池配置。
     */
    public record PoolConfig(
            int maxTotal,
            int maxIdle,
            int minIdle,
            long maxIdleTimeMs,
            long maxLifetimeMs
    ) {
        public static PoolConfig defaultConfig() {
            return new PoolConfig(10, 5, 2, TimeUnit.MINUTES.toMillis(30), TimeUnit.HOURS.toMillis(4));
        }
    }

    private final Map<SandboxProfile, LinkedBlockingDeque<SandboxInstance>> idlePools = new ConcurrentHashMap<>();
    private final Map<String, SandboxInstance> activeInstances = new ConcurrentHashMap<>();
    private final Map<SandboxProfile, PoolConfig> poolConfigs = new ConcurrentHashMap<>();
    private final AtomicInteger instanceCounter = new AtomicInteger(0);
    private final SandboxReusePolicy reusePolicy;

    public SandboxPool(SandboxReusePolicy reusePolicy) {
        this.reusePolicy = reusePolicy;
        for (SandboxProfile profile : SandboxProfile.values()) {
            idlePools.put(profile, new LinkedBlockingDeque<>());
            poolConfigs.put(profile, PoolConfig.defaultConfig());
        }
    }

    /**
     * 获取池配置。
     */
    public PoolConfig getPoolConfig(SandboxProfile profile) {
        return poolConfigs.getOrDefault(profile, PoolConfig.defaultConfig());
    }

    /**
     * 更新池配置。
     */
    public void updatePoolConfig(SandboxProfile profile, PoolConfig config) {
        poolConfigs.put(profile, config);
        log.info("更新沙箱池配置: profile={}, maxTotal={}, maxIdle={}",
                profile.getDisplayName(), config.maxTotal(), config.maxIdle());
    }

    /**
     * 分配沙箱实例（{@link RiskLevel} 枚举版，推荐使用）。
     * <p>优先从空闲池获取符合条件的实例，否则创建新实例。
     *
     * @param profile   所需的沙箱 Profile
     * @param taskId    任务 ID
     * @param riskLevel 风险等级（影响是否允许复用）
     * @return 沙箱实例
     */
    public SandboxInstance acquire(SandboxProfile profile, String taskId, RiskLevel riskLevel) {
        log.info("分配沙箱: profile={}, taskId={}, riskLevel={}",
                profile.getDisplayName(), taskId, riskLevel);

        PoolConfig config = poolConfigs.getOrDefault(profile, PoolConfig.defaultConfig());

        // 1. 尝试从空闲池获取
        SandboxInstance idleInstance = idlePools.get(profile).pollFirst();
        if (idleInstance != null) {
            // 检查是否可以复用
            if (reusePolicy.canReuse(idleInstance, taskId, riskLevel)) {
                return activateInstance(idleInstance, taskId);
            } else {
                // 不允许复用，销毁旧实例
                destroyInstance(idleInstance);
            }
        }

        // 2. 创建新实例
        int totalCount = getTotalCount(profile);
        if (totalCount >= config.maxTotal()) {
            log.warn("沙箱池已满: profile={}, current={}, max={}", profile.getDisplayName(), totalCount, config.maxTotal());
            // 等待空闲实例
            try {
                idleInstance = idlePools.get(profile).poll(5, TimeUnit.SECONDS);
                if (idleInstance != null && reusePolicy.canReuse(idleInstance, taskId, riskLevel)) {
                    return activateInstance(idleInstance, taskId);
                } else if (idleInstance != null) {
                    destroyInstance(idleInstance);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("等待沙箱实例被中断");
            }
            throw new IllegalStateException("沙箱池已满，无法分配实例: " + profile.getDisplayName());
        }

        return createInstance(profile, taskId);
    }

    /**
     * 分配沙箱实例（String 版，向后兼容）。
     *
     * @param profile   所需的沙箱 Profile
     * @param taskId    任务 ID
     * @param riskLevel 风险等级字符串（low/medium/high/critical）
     * @return 沙箱实例
     * @deprecated 优先使用 {@link #acquire(SandboxProfile, String, RiskLevel)}
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public SandboxInstance acquire(SandboxProfile profile, String taskId, String riskLevel) {
        return acquire(profile, taskId, RiskLevel.fromString(riskLevel));
    }

    /**
     * 释放沙箱实例。
     * <p>将实例归还到空闲池，或根据策略销毁。
     *
     * @param instance 要释放的实例
     */
    public void release(SandboxInstance instance) {
        log.info("释放沙箱: id={}, profile={}, useCount={}",
                instance.id(), instance.profile().getDisplayName(), instance.useCount());

        activeInstances.remove(instance.id());

        // 检查是否超过最大生命周期
        long lifetime = Instant.now().toEpochMilli() - instance.createdAt().toEpochMilli();
        if (lifetime > poolConfigs.getOrDefault(instance.profile(), PoolConfig.defaultConfig()).maxLifetimeMs()) {
            log.info("沙箱超过最大生命周期，销毁: id={}, lifetime={}ms", instance.id(), lifetime);
            destroyInstance(instance);
            return;
        }

        // 检查是否超过最大使用次数
        int maxUseCount = 100; // 可配置
        if (instance.useCount() >= maxUseCount) {
            log.info("沙箱超过最大使用次数，销毁: id={}, useCount={}", instance.id(), instance.useCount());
            destroyInstance(instance);
            return;
        }

        // 归还到空闲池
        SandboxInstance updated = new SandboxInstance(
                instance.id(), instance.profile(), SandboxState.IDLE,
                instance.createdAt(), Instant.now(), null,
                instance.useCount(), instance.totalUsageMs()
        );
        idlePools.get(instance.profile()).addLast(updated);
    }

    /**
     * 强制销毁沙箱实例。
     */
    public void destroy(SandboxInstance instance) {
        log.info("强制销毁沙箱: id={}", instance.id());
        activeInstances.remove(instance.id());
        idlePools.get(instance.profile()).remove(instance);
        destroyInstance(instance);
    }

    /**
     * 获取指定 Profile 的所有实例（包括空闲和使用中）。
     */
    public List<SandboxInstance> getInstances(SandboxProfile profile) {
        List<SandboxInstance> instances = new ArrayList<>();
        instances.addAll(idlePools.getOrDefault(profile, new LinkedBlockingDeque<>()));
        instances.addAll(activeInstances.values().stream()
                .filter(i -> i.profile() == profile)
                .collect(Collectors.toList()));
        return instances;
    }

    /**
     * 获取所有活跃的沙箱实例。
     */
    public Collection<SandboxInstance> getActiveInstances() {
        return Collections.unmodifiableCollection(activeInstances.values());
    }

    /**
     * 获取池状态统计。
     */
    public Map<String, Object> getPoolStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (SandboxProfile profile : SandboxProfile.values()) {
            Map<String, Object> profileStatus = new LinkedHashMap<>();
            int idle = idlePools.getOrDefault(profile, new LinkedBlockingDeque<>()).size();
            int active = (int) activeInstances.values().stream()
                    .filter(i -> i.profile() == profile).count();
            PoolConfig config = poolConfigs.getOrDefault(profile, PoolConfig.defaultConfig());
            profileStatus.put("idle", idle);
            profileStatus.put("active", active);
            profileStatus.put("total", idle + active);
            profileStatus.put("maxTotal", config.maxTotal());
            status.put(profile.getDisplayName(), profileStatus);
        }
        return status;
    }

    /**
     * 清理过期的空闲实例。
     */
    public int cleanupExpiredInstances() {
        int cleanedCount = 0;
        long now = Instant.now().toEpochMilli();

        for (SandboxProfile profile : SandboxProfile.values()) {
            PoolConfig config = poolConfigs.getOrDefault(profile, PoolConfig.defaultConfig());
            LinkedBlockingDeque<SandboxInstance> idleQueue = idlePools.get(profile);

            Iterator<SandboxInstance> it = idleQueue.iterator();
            while (it.hasNext()) {
                SandboxInstance instance = it.next();
                if (instance.isExpired(config.maxIdleTimeMs())) {
                    it.remove();
                    destroyInstance(instance);
                    cleanedCount++;
                    log.info("清理过期沙箱: id={}, idleTime={}ms",
                            instance.id(),
                            now - (instance.lastUsedAt() != null ? instance.lastUsedAt().toEpochMilli() : instance.createdAt().toEpochMilli()));
                }
            }
        }

        return cleanedCount;
    }

    /**
     * 创建新的沙箱实例。
     * <p>实际部署时，步骤 2 需要对接底层沙箱客户端（Docker/K8s/E2B 等），
     * 调用对应的 createContainer / createPod / createSandbox API。
     *
     * @param profile 沙箱 Profile（决定镜像、资源配额、网络策略等）
     * @param taskId  目标任务 ID
     * @return 已就绪的 IN_USE 实例
     */
    private SandboxInstance createInstance(SandboxProfile profile, String taskId) {
        // 步骤 1：生成唯一 ID 并记录日志
        String id = "sandbox-" + instanceCounter.incrementAndGet();
        log.info("创建新沙箱: id={}, profile={}, image={}", id, profile.getDisplayName(), profile.getDockerImage());

        // 步骤 2：标记实例为 INITIALIZING（初始化中）——实际部署时在此对接底层 API：
        //   Docker: dockerClient.createContainer(profile.toOptions()) → dockerClient.startContainer(cid)
        //   K8s:    k8sClient.provisionTeamSandbox(team, opts) → k8sClient.execInPod(team, opts, command)
        //   E2B:    e2bClient.createSandbox(templateId, opts)
        SandboxInstance instance = new SandboxInstance(
                id, profile, SandboxState.INITIALIZING,
                Instant.now(), Instant.now(), taskId, 0, 0
        );

        // 步骤 3：标记实例为 IN_USE（就绪），移入活跃池
        SandboxInstance ready = new SandboxInstance(
                id, profile, SandboxState.IN_USE,
                instance.createdAt(), Instant.now(), taskId, 1, 0
        );
        activeInstances.put(id, ready);

        log.info("沙箱初始化完成: id={}", id);
        return ready;
    }

    private SandboxInstance activateInstance(SandboxInstance instance, String taskId) {
        log.info("激活空闲沙箱: id={}, taskId={}", instance.id(), taskId);

        SandboxInstance activated = new SandboxInstance(
                instance.id(), instance.profile(), SandboxState.IN_USE,
                instance.createdAt(), Instant.now(), taskId,
                instance.useCount() + 1, instance.totalUsageMs()
        );
        activeInstances.put(instance.id(), activated);
        return activated;
    }

    private void destroyInstance(SandboxInstance instance) {
        log.info("销毁沙箱实例: id={}, profile={}", instance.id(), instance.profile().getDisplayName());
        // 实际生产中这里会调用 Docker API 销毁容器
    }

    private int getTotalCount(SandboxProfile profile) {
        return idlePools.getOrDefault(profile, new LinkedBlockingDeque<>()).size()
                + (int) activeInstances.values().stream().filter(i -> i.profile() == profile).count();
    }
}
