package io.etclovg.codepilot.foundation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 成本配置。
 * <p>对应书中 Ch01 §1.3 —— Agent 运行时的成本预算配置。
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.agent.cost")
public class AgentCostConfig {

    private double maxDailyCost = 100.0;
    private double maxRequestCost = 1.0;
    private double warningThreshold = 0.7;
    private double criticalThreshold = 0.9;
    private String currency = "USD";

    public double getMaxDailyCost() { return maxDailyCost; }
    public void setMaxDailyCost(double maxDailyCost) { this.maxDailyCost = maxDailyCost; }

    public double getMaxRequestCost() { return maxRequestCost; }
    public void setMaxRequestCost(double maxRequestCost) { this.maxRequestCost = maxRequestCost; }

    public double getWarningThreshold() { return warningThreshold; }
    public void setWarningThreshold(double warningThreshold) { this.warningThreshold = warningThreshold; }

    public double getCriticalThreshold() { return criticalThreshold; }
    public void setCriticalThreshold(double criticalThreshold) { this.criticalThreshold = criticalThreshold; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}