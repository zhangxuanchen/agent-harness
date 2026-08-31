package io.etclovg.codepilot;

import io.etclovg.codepilot.memory.ContextBudgetAdvisor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * CodePilot 统一启动入口。
 *
 * <p>这是整个项目的唯一 Spring Boot 应用，负责扫描所有章节模块的组件。
 * 各章节模块（ch01 ~ ch17）作为纯库模块引入，不再独立启动。
 *
 * <p>启动方式：
 * <pre>{@code
 * cd codepilot-starter
 * mvn spring-boot:run
 * }</pre>
 */
@SpringBootApplication
public class CodepilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodepilotApplication.class, args);
    }

    @Bean
    public ContextBudgetAdvisor.TokenCountEstimator tokenCountEstimator() {
        return ContextBudgetAdvisor.defaultEstimator();
    }

    @Bean
    public ContextBudgetAdvisor.Budget contextBudget() {
        return ContextBudgetAdvisor.Budget.allocation()
            .maxTokens(128_000)
            .system(0.10)
            .task(0.05)
            .retrieval(0.15)
            .tools(0.40)
            .headroom(0.30)
            .build();
    }
}
