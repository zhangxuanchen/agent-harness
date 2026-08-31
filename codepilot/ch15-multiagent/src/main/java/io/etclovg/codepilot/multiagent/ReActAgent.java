package io.etclovg.codepilot.multiagent;

/**
 * AgentScope 框架桩 — 仅用于本章代码编译对齐，非生产实现。
 * 对应书中 Ch15 §15.4.1：ReAct 推理 Agent，作为 DAG 分解的 Planner。
 * 书中用法：{@code plannerAgent.call(prompt).arg("task", x).content()} 返回 LLM 文本。
 */
public class ReActAgent {

    public PromptCall call(String prompt) {
        return new PromptCall(prompt);
    }

    /** 概念示例桩：链式调用 .arg(...).content() */
    public static final class PromptCall {
        private final String prompt;
        private String rendered;

        PromptCall(String prompt) {
            this.prompt = prompt;
            this.rendered = prompt;
        }

        public PromptCall arg(String name, Object value) {
            // 概念示例：生产中按模板替换占位符
            this.rendered = prompt.replace("{" + name + "}", String.valueOf(value));
            return this;
        }

        public String content() {
            return rendered;
        }
    }
}
