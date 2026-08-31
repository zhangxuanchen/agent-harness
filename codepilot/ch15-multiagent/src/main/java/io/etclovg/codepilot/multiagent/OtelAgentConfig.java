package io.etclovg.codepilot.multiagent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 多 Agent 调用链 OTel 埋点配置。对应书中 Ch15 §15.7.1 · 概念示例。
 * <p>核心是将 sessionId（业务维度）和 traceId（技术维度）绑定，
 * 让运维人员在 Jaeger 面板里可以用 sessionId 反查整条调用链。
 * <p>框架：AgentScope 2.x + Spring Boot 3.5+ + OpenTelemetry SDK 1.39+。
 * 生产部署需下载 opentelemetry-javaagent 对应版本，通过 JVM 参数
 * {@code -javaagent:./opentelemetry-javaagent.jar} 注入，无需改代码。
 *
 * <p><b>实现说明</b>：书中以 SpanExporter + span.toBuilder() 示意属性注入，
 * 但 OTel SDK 中 SpanData 不可变——可编译的真实做法是在 SpanProcessor.onStart
 * 拿到 ReadWriteSpan 时注入属性（此时 span 可写）。此处按真实 API 实现，保留书中意图：
 * 把 MDC 中的 sessionId/agentRole/costUsd 注入为 OTel 自定义 Span 属性。
 */
@Configuration
public class OtelAgentConfig {

    /**
     * 自定义 Span 属性：注入 Agent 特有字段，在 OTel 后端面板可直接筛选。
     * OTel 标准属性只覆盖了 HTTP/DB，Agent 语义靠自定义属性补齐。
     */
    @Bean
    public SpanProcessor agentAttributeProcessor() {
        return new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
                // RuntimeContext 中 sessionId → OTel SpanAttribute
                String sessionId = MDC.get("sessionId");
                if (sessionId != null) {
                    span.setAttribute(AttributeKey.stringKey("agent.session_id"), sessionId);
                    span.setAttribute(AttributeKey.stringKey("agent.role"),
                        orDefault(MDC.get("agentRole"), "UNKNOWN"));
                    span.setAttribute(AttributeKey.doubleKey("agent.cost_usd"),
                        parseDouble(orDefault(MDC.get("costUsd"), "0")));
                }
            }

            @Override
            public boolean isStartRequired() {
                return true;
            }

            @Override
            public void onEnd(ReadableSpan span) {
                // SpanData 在 onEnd 时已不可变，属性注入在 onStart 完成
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode forceFlush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public void close() {
                shutdown();
            }
        };
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
