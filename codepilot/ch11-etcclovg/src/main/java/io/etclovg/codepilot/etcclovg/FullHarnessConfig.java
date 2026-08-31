package io.etclovg.codepilot.etcclovg;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 全链路 Harness 配置。
 * <p>对应书中 Ch11 —— ETCLOVG 七层的完整配置集合。
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.harness")
public class FullHarnessConfig {

    private boolean executionEnabled = true;
    private String modelRef = "dashscope:qwen-plus";
    private boolean toolingEnabled = true;
    private boolean contextEnabled = true;
    private boolean lifecycleEnabled = true;
    private boolean observabilityEnabled = true;
    private boolean verificationEnabled = true;
    private boolean governanceEnabled = true;

    public boolean isExecutionEnabled() { return executionEnabled; }
    public void setExecutionEnabled(boolean v) { this.executionEnabled = v; }

    public String getModelRef() { return modelRef; }
    public void setModelRef(String v) { this.modelRef = v; }

    public boolean isToolingEnabled() { return toolingEnabled; }
    public void setToolingEnabled(boolean v) { this.toolingEnabled = v; }

    public boolean isContextEnabled() { return contextEnabled; }
    public void setContextEnabled(boolean v) { this.contextEnabled = v; }

    public boolean isLifecycleEnabled() { return lifecycleEnabled; }
    public void setLifecycleEnabled(boolean v) { this.lifecycleEnabled = v; }

    public boolean isObservabilityEnabled() { return observabilityEnabled; }
    public void setObservabilityEnabled(boolean v) { this.observabilityEnabled = v; }

    public boolean isVerificationEnabled() { return verificationEnabled; }
    public void setVerificationEnabled(boolean v) { this.verificationEnabled = v; }

    public boolean isGovernanceEnabled() { return governanceEnabled; }
    public void setGovernanceEnabled(boolean v) { this.governanceEnabled = v; }
}