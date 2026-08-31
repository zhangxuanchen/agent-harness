package io.etclovg.codepilot.tools;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具描述验证器。
 * <p>对应书中 Ch05 §5.4.2 —— 验证工具描述的完整性和规范性。
 */
@Component
public class ToolDescriptionValidator {

    /**
     * 验证结果。
     */
    public record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {}

    /**
     * 验证工具描述。
     */
    public ValidationResult validate(String name, String description, Map<String, Object> schema) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (name == null || name.isBlank()) {
            errors.add("工具名称不能为空");
        }
        if (description == null || description.isBlank()) {
            warnings.add("工具描述为空，建议添加描述以提高工具发现率");
        }
        if (schema == null || schema.isEmpty()) {
            warnings.add("工具输入/输出 schema 未定义");
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }
}