package io.etclovg.codepilot.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 网络策略——从完全断网到白名单代理。
 * <p>对应书中 Ch04 §4.3.2 —— 沙箱网络访问控制策略。
 * <p>封装网络访问模式、白名单域名、出站代理与审计日志路径，
 * 通过 {@link #toDockerArgs()} 转换为 Docker 网络参数；所有外连经代理过滤并记录审计日志，
 * 供 O 层可观测性分析与 G 层合规审计使用。
 */
public class NetworkPolicy {

    /** 网络策略类型 */
    public enum Type { DENY_ALL, WHITELIST, BLACKLIST, ALLOW_ALL }

    private final Type type;
    private final Set<String> allowedDomains;
    private final String proxyUrl;
    private final Path auditLogPath;

    public NetworkPolicy(Type type, Set<String> allowedDomains, String proxyUrl, Path auditLogPath) {
        this.type = type;
        this.allowedDomains = allowedDomains == null ? Set.of() : Set.copyOf(allowedDomains);
        this.proxyUrl = proxyUrl;
        this.auditLogPath = auditLogPath;
    }

    /** 默认策略：白名单模式（pypi/npm/maven/OpenAI/Anthropic + Squid 代理 + 审计日志） */
    public static NetworkPolicy defaultWhitelist() {
        return new NetworkPolicy(
            Type.WHITELIST,
            Set.of(
                "pypi.org",              // Python 包
                "registry.npmjs.org",    // Node.js 包
                "repo1.maven.org",       // Java 依赖
                "api.openai.com",        // OpenAI API
                "api.anthropic.com"      // Anthropic API
            ),
            "http://proxy:3128",       // Squid 代理
            Path.of("/var/log/agent/network-audit.log")
        );
    }

    /** 完全断网 */
    public static NetworkPolicy denyAll() {
        return new NetworkPolicy(Type.DENY_ALL, Set.of(), null, null);
    }

    /** 全放开（⚠️ 仅开发环境） */
    public static NetworkPolicy allowAll() {
        return new NetworkPolicy(Type.ALLOW_ALL, Set.of(), null, null);
    }

    /** 白名单模式：仅放行指定域名，其余走代理拦截 */
    public static NetworkPolicy whitelist(String... domains) {
        return new NetworkPolicy(
            Type.WHITELIST,
            Set.of(domains),
            "http://proxy:3128",
            Path.of("/var/log/agent/network-audit.log")
        );
    }

    /** 转换为 Docker 网络参数 */
    public List<String> toDockerArgs() {
        return switch (type) {
            case DENY_ALL   -> List.of("--network=none");
            case WHITELIST  -> List.of("--network=agent-net", "--dns=proxy-dns");  // 自定义 DNS 强制走代理
            case ALLOW_ALL  -> List.of("--network=host");                          // ⚠️ 仅开发环境
            case BLACKLIST  -> List.of("--network=agent-net");
        };
    }

    /** 运行时校验：目标主机是否允许访问 */
    public boolean checkAccess(String targetHost) {
        if (targetHost == null || targetHost.isBlank()) return false;
        return switch (type) {
            case DENY_ALL   -> false;
            case ALLOW_ALL  -> true;
            case WHITELIST  -> allowedDomains.stream().anyMatch(targetHost::endsWith);
            case BLACKLIST  -> allowedDomains.stream().noneMatch(targetHost::endsWith);
        };
    }

    public Type getType() { return type; }
    public Set<String> getAllowedDomains() { return allowedDomains; }
    public String getProxyUrl() { return proxyUrl; }
    public Path getAuditLogPath() { return auditLogPath; }
}
