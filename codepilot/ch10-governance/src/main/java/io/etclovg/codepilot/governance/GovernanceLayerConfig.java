package io.etclovg.codepilot.governance;

import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * G 层 · 治理层配置——显式声明 G 层九个组件的装配顺序。
 *
 * <p>代码库中的治理组件均为 @Component（继承 AbstractLayerMiddleware），
 * 由 Spring 自动扫描注册到 ReActAgent 的 Middleware 链。此配置类提供显式
 * 装配顺序的备选方案——通过 @Order 注解控制 onAgent 钩子的执行次序：
 * 输入守卫最先、审计归责最后（覆盖全过程）。
 *
 * <h3>真实组件清单（均位于 codepilot/ch10-governance/）</h3>
 * <ol>
 *   <li>InputGuardAdvisor — 输入检查点（executeGuardPipeline 三级管线 L1 正则→L2 分类器→L3 LLM）</li>
 *   <li>ConstitutionValidator — YAML 声明式宪法（scope+action 二维模型）</li>
 *   <li>ToolGuardAdvisor — 工具调用检查点（白名单 + 参数 + 频率 + 审批）</li>
 *   <li>OutputGuardAdvisor — 输出检查点（PII 脱敏 + 有害内容过滤）</li>
 *   <li>SessionMonitor — 会话检查点（异常模式检测，doOnComplete 异步）</li>
 *   <li>AuditLogAdvisor — 6 层决策归因链 + Merkle WORM 审计</li>
 * </ol>
 *
 * <p>对应书中 Ch10 末尾锚点代码——G 层组件装配。
 */
@Configuration
public class GovernanceLayerConfig {

    /** 钩子 1: INPUT → L1/L2/L3 三级管线防注入 */
    @Bean
    @Order(1)
    public AbstractLayerMiddleware inputCheckpoint(InputGuardAdvisor advisor) {
        return advisor;
    }

    /** 钩子 2: 声明式宪法 scope/action 规则机械强制 */
    @Bean
    @Order(2)
    public AbstractLayerMiddleware constitutionCheckpoint(ConstitutionValidator validator) {
        return validator;
    }

    /** 钩子 3: 工具调用 → 白名单 + 参数 + 频率 + 审批 */
    @Bean
    @Order(3)
    public AbstractLayerMiddleware toolCheckpoint(ToolGuardAdvisor advisor) {
        return advisor;
    }

    /** 钩子 4: 输出 → PII 脱敏 + 有害内容过滤 */
    @Bean
    @Order(4)
    public AbstractLayerMiddleware outputCheckpoint(OutputGuardAdvisor advisor) {
        return advisor;
    }

    /** 钩子 5: 会话 → 异常模式检测（后置异步） */
    @Bean
    @Order(5)
    public AbstractLayerMiddleware sessionCheckpoint(SessionMonitor monitor) {
        return monitor;
    }

    /** 钩子 6: 归责 → 6 层归因链 + Merkle WORM 审计（最后，覆盖全过程） */
    @Bean
    @Order(6)
    public AbstractLayerMiddleware auditCheckpoint(AuditLogAdvisor advisor) {
        return advisor;
    }
}
