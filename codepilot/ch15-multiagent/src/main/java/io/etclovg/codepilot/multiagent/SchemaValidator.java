package io.etclovg.codepilot.multiagent;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Schema 校验器。对应书中 Ch15 §15.3 Phase 1。
 * 检查输出 JSON Schema 是否与期望格式匹配。
 * 桩实现：用基本正则/关键字匹配。概念示例。
 */
@Component
public class SchemaValidator {
    public boolean matches(ConsistencyVerifier.AgentOutput output) {
        Object p = output.payload();
        if (p == null) return false;
        String s = p.toString();
        if (s.startsWith("{") || s.startsWith("[")) {
            return countPair(s, '{', '}') && countPair(s, '[', ']');
        }
        return true;
    }
    private boolean countPair(String s, char o, char c) {
        int depth = 0;
        for (char ch : s.toCharArray()) { if (ch == o) depth++; else if (ch == c) depth--; }
        return depth == 0;
    }
}
