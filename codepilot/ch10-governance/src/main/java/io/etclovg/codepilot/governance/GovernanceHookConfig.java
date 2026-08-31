package io.etclovg.codepilot.governance;

import io.etclovg.codepilot.core.AbstractLayerMiddleware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * G 层 · 治理钩子配置——显式声明四个检查点的装配顺序。
 *
 * <p>代码库中的治理组件（InputGuardAdvisor、ToolGuardAdvisor 等）均为 @Component，
 * Spring 自动扫描注册到 ReActAgent 的 Middleware 链。此配置类提供显式装配顺序的
 * 备选方案——通过 @Order 注解控制 onAgent 钩子的执行次序。
 * <p>对应书中 Ch10 §KP 10.2.1 — 四个检查点的装配。
 */
@Configuration
public class GovernanceHookConfig {

    /** 钩子 1: 输入检查点 → 防注入（L1/L2/L3 管线） */
    @Bean
    @Order(1)
    public AbstractLayerMiddleware inputCheckpoint(InputGuardAdvisor advisor) {
        return advisor;
    }

    /** 钩子 2: 工具调用检查点 → 白名单 + 参数 + 频率 + 审批 */
    @Bean
    @Order(2)
    public AbstractLayerMiddleware toolCheckpoint(ToolGuardAdvisor advisor) {
        return advisor;
    }

    /** 钩子 3: 输出检查点 → 防 PII 泄漏 + 有害内容 */
    @Bean
    @Order(3)
    public AbstractLayerMiddleware outputCheckpoint(OutputGuardAdvisor advisor) {
        return advisor;
    }

    /** 钩子 4: 会话检查点 → 异常检测（doOnComplete 后置分析） */
    @Bean
    @Order(4)
    public AbstractLayerMiddleware sessionCheckpoint(SessionMonitor monitor) {
        return monitor;
    }
}
