package io.etclovg.codepilot.foundation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CodePilot 提示词配置。
 * <p>对应书中 Ch01 §1.3 —— Agent 系统提示词和角色设定的配置。
 */
@Configuration
@ConfigurationProperties(prefix = "codepilot.prompt")
public class CodePilotPromptConfig {

    private String systemPrompt = "You are CodePilot, an intelligent coding assistant.";
    private String role = "senior-developer";
    private String language = "zh-CN";
    private List<String> capabilities = new ArrayList<>(List.of("code-generation", "refactoring", "debugging"));
    private Map<String, String> customInstructions;

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }

    public Map<String, String> getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(Map<String, String> customInstructions) { this.customInstructions = customInstructions; }
}