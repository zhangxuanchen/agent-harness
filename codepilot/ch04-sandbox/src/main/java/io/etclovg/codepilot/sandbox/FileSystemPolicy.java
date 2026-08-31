package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件系统操作策略。
 * <p>对应书中 Ch04 §4.3.2 —— 沙箱文件系统访问控制策略。
 * <p>根据任务风险等级与沙箱 Profile，决定允许的读写路径、
 * 是否允许网络挂载以及使用的后端类型，作为 {@code SandboxAdvisor}
 * 决策沙箱配置时的输入。
 * <p>同时支持两种使用方式：
 * <ul>
 *   <li>Spring Bean 模式（{@code @Component} 注入，使用默认读写规则）</li>
 *   <li>Fluent Builder 模式（{@link #writable(String)} 链式构造自定义策略）</li>
 * </ul>
 */
@Component
public class FileSystemPolicy {

    private static final Logger log = LoggerFactory.getLogger(FileSystemPolicy.class);

    /** 默认允许读取的目录前缀 */
    private static final Set<String> DEFAULT_READ_PREFIXES = Set.of("/workspace", "/tmp", "/data");

    /** 默认禁止写入的目录前缀 */
    private static final Set<String> DENIED_WRITE_PREFIXES = Set.of("/etc", "/usr", "/bin", "/sbin", "/root");

    // ===== Fluent Builder 字段 =====

    /** 可写路径前缀（Fluent Builder 模式下使用） */
    private Set<String> writablePaths = new HashSet<>();

    /** 只读路径前缀（Fluent Builder 模式下使用） */
    private Set<String> readOnlyPaths = new HashSet<>();

    /** 明确排除的路径前缀（白名单投影时不注入容器） */
    private Set<String> excludedPaths = new HashSet<>();

    /** 是否通过 Fluent Builder 创建 */
    private boolean builderMode = false;

    public FileSystemPolicy() {
        // Spring Bean 默认构造
    }

    private FileSystemPolicy(boolean builderMode) {
        this.builderMode = builderMode;
    }

    // ===== Fluent Builder 静态工厂方法（对齐书中 §4.7.2 API） =====

    /**
     * 创建 Fluent Builder 入口（推荐方式，支持 exclude/只读/可写的组合配置）。
     * <p>对应书中 §4.5.3 五层防御的文件系统白名单投影配置。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建可写路径策略（兼容旧 API，推荐使用 {@link #builder()}）。
     * <p>对应书中 {@code FileSystemPolicy.writable("/workspace")}。
     *
     * @param path 允许写入的路径前缀
     * @return 新策略实例（可继续链式调用）
     */
    public static FileSystemPolicy writable(String path) {
        FileSystemPolicy policy = new FileSystemPolicy(true);
        policy.writablePaths.add(path);
        return policy;
    }

    /**
     * 追加只读路径（Fluent Builder 链式方法）。
     * <p>对应书中 {@code .readOnly("/")}。
     *
     * @param path 只读的路径前缀
     * @return 当前策略实例（链式调用）
     */
    public FileSystemPolicy readOnly(String path) {
        this.readOnlyPaths.add(path);
        return this;
    }

    /**
     * 追加可写路径（Fluent Builder 链式方法）。
     *
     * @param path 允许写入的路径前缀
     * @return 当前策略实例（链式调用）
     */
    public FileSystemPolicy writablePath(String path) {
        this.writablePaths.add(path);
        return this;
    }

    // ===== 策略查询方法 =====

    /**
     * 根据风险等级选择文件系统存储类型。
     *
     * @param riskLevel 风险等级枚举
     * @return 推荐的文件系统存储类型
     */
    public FileSystemStorageType selectBackend(RiskLevel riskLevel) {
        FileSystemStorageType backend = switch (riskLevel == null ? RiskLevel.MEDIUM : riskLevel) {
            case CRITICAL, HIGH -> FileSystemStorageType.OVERLAY;
            case MEDIUM -> FileSystemStorageType.VOLUME;
            case LOW -> FileSystemStorageType.BIND;
        };
        log.debug("选择文件系统后端: risk={}, backend={}", riskLevel, backend);
        return backend;
    }

    /**
     * 根据风险等级字符串选择文件系统存储类型（向后兼容）。
     *
     * @param riskLevel 风险等级字符串
     * @return 推荐的文件系统存储类型
     * @deprecated 优先使用 {@link #selectBackend(RiskLevel)}
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public FileSystemStorageType selectBackend(String riskLevel) {
        return selectBackend(RiskLevel.fromString(riskLevel));
    }

    /**
     * 校验给定路径是否允许读取。
     *
     * @param path 文件路径
     * @return 允许返回 true
     */
    public boolean canRead(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (builderMode) {
            // Builder 模式：可写路径和只读路径都允许读
            return writablePaths.stream().anyMatch(path::startsWith)
                    || readOnlyPaths.stream().anyMatch(path::startsWith);
        }
        return DEFAULT_READ_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * 校验给定路径是否允许写入。
     *
     * @param path 文件路径
     * @return 允许返回 true
     */
    public boolean canWrite(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (builderMode) {
            // Builder 模式：仅可写路径允许写，只读路径禁止写
            if (readOnlyPaths.stream().anyMatch(path::startsWith)) {
                return false;
            }
            return writablePaths.stream().anyMatch(path::startsWith);
        }
        if (DENIED_WRITE_PREFIXES.stream().anyMatch(path::startsWith)) {
            return false;
        }
        return DEFAULT_READ_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * 返回禁止写入的目录前缀列表。
     *
     * @return 不可变列表
     */
    public List<String> deniedWritePrefixes() {
        if (builderMode) {
            return List.copyOf(readOnlyPaths);
        }
        return List.copyOf(DENIED_WRITE_PREFIXES);
    }

    /** 获取可写路径集合 */
    public Set<String> getWritablePaths() {
        return Set.copyOf(writablePaths);
    }

    /** 获取只读路径集合 */
    public Set<String> getReadOnlyPaths() {
        return Set.copyOf(readOnlyPaths);
    }

    /** 获取明确排除的路径集合（白名单投影时这些路径不会注入容器） */
    public Set<String> getExcludedPaths() {
        return Set.copyOf(excludedPaths);
    }

    // ===== Builder 内部类 =====

    /**
     * Fluent Builder——支持可写路径、只读路径、排除路径的组合配置。
     * <p>对应书中 §4.5.3 五层防御的文件系统白名单投影配置示例。
     * <p>典型用法：
     * <pre>{@code
     * FileSystemPolicy policy = FileSystemPolicy.builder()
     *     .writable("/workspace")
     *     .readOnly("/config")
     *     .exclude("/sessions")
     *     .exclude("/secrets")
     *     .build();
     * }</pre>
     */
    public static class Builder {

        private final Set<String> writable = new HashSet<>();
        private final Set<String> readOnly = new HashSet<>();
        private final Set<String> excluded = new HashSet<>();

        /** 追加可写路径 */
        public Builder writable(String path) {
            writable.add(path);
            return this;
        }

        /** 追加只读路径 */
        public Builder readOnly(String path) {
            readOnly.add(path);
            return this;
        }

        /** 追加排除路径（白名单投影时不注入容器） */
        public Builder exclude(String path) {
            excluded.add(path);
            return this;
        }

        /** 构建最终策略实例 */
        public FileSystemPolicy build() {
            FileSystemPolicy policy = new FileSystemPolicy(true);
            policy.writablePaths.addAll(writable);
            policy.readOnlyPaths.addAll(readOnly);
            policy.excludedPaths.addAll(excluded);
            return policy;
        }
    }
}
