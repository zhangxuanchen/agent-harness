package io.etclovg.codepilot.foundation;

import java.util.List;

/**
 * Agent 技术栈三波递进模型
 * Wave 1: 提示工程 (2022-2023)
 * Wave 2: RAG + 工具调用 (2023-2024)
 * Wave 3: 自主 Agent (2024-2025)
 */
public class WaveModel {

    public enum Wave {
        WAVE_1_PROMPT("第一波：提示工程", "System Prompt, Few-shot, CoT"),
        WAVE_2_RAG("第二波：RAG + 工具调用", "向量数据库, Function Calling, @Tool"),
        WAVE_3_AGENT("第三波：自主 Agent", "ReAct Loop, MCP/A2A, 多Agent协作");

        private final String name;
        private final String technologies;

        Wave(String name, String technologies) {
            this.name = name;
            this.technologies = technologies;
        }

        public String getName() { return name; }
        public String getTechnologies() { return technologies; }
    }

    public static List<Wave> getWaves() {
        return List.of(Wave.WAVE_1_PROMPT, Wave.WAVE_2_RAG, Wave.WAVE_3_AGENT);
    }
}