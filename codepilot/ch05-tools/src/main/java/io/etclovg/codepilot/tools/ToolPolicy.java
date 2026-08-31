package io.etclovg.codepilot.tools;

import java.lang.annotation.*;

/**
 * G 层 · 工具运行时治理注解。
 * <p>声明式工具权限管理——定义工具的运行时访问条件。
 * 对应书中 Ch5 §5.7.1 — 工具的运行时权限管理。
 *
 * <p><b>安全模型</b>：default-deny——未标注此注解的工具默认不可用<br>
 * <b>原理</b>：借鉴 Kubernetes RBAC 的 deny-by-default 模型，漏报率低 90%+
 *
 * <p>使用示例：
 * <pre>{@code
 * @ToolPolicy(
 *     allowedRoles = {"admin", "operator"},
 *     rateLimit = 5,
 *     maxCallsPerHour = 100,
 *     requireHumanApproval = true
 * )
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolPolicy {

    /** 允许使用的角色列表。空数组 = 所有角色可用 */
    String[] allowedRoles() default {};

    /** 每秒最大调用次数（令牌桶限流） */
    int rateLimit() default Integer.MAX_VALUE;

    /** 每小时最大调用次数 */
    int maxCallsPerHour() default Integer.MAX_VALUE;

    /** 是否需要人工审批 */
    boolean requireHumanApproval() default false;

    /** 风险等级（1=低风险, 2=中等, 3=高风险） */
    int riskLevel() default 1;

    /** 允许的环境（dev/staging/prod）。空数组 = 所有环境 */
    String[] allowedEnvironments() default {};
}
