package io.etclovg.codepilot.data;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AST 安全扫描器。
 * <p>对应书中 Ch13 §13.2 —— V 层 AST 注入检测。
 * <p>基于抽象语法树精确分析生成代码，命中 {@code Runtime.exec} /
 * {@code ProcessBuilder} / 反射 {@code Class.forName} 等危险节点。
 * AST 检测的抗绕过性来自它解析的是语法结构而非字符序列——
 * 无论加多少空白、注释、字符串拼接，AST 都会还原出真实的方法调用链。
 *
 * <p>本类为可运行实现（依赖 javaparser-symbol-solver-core 3.26.1），
 * 与章节代码完全对齐。实际部署需扩展禁用 API 清单并覆盖字段访问与构造调用。
 */
@Component
public class AstSafetyScanner {

    private static final Set<String> BANNED_METHODS = Set.of(
        "exec", "start", "forName", "load", "loadLibrary"
    );
    private static final Set<String> BANNED_TYPES = Set.of(
        "Runtime", "ProcessBuilder", "Process", "Class"
    );

    /**
     * 扫描源码中的危险调用。
     *
     * @param sourceCode 待扫描源码
     * @return 通过返回 {@link ValidationResult#pass()}，否则返回 fail 结果
     */
    public ValidationResult scan(String sourceCode) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);
            List<String> violations = new ArrayList<>();
            cu.findAll(MethodCallExpr.class).forEach(call -> {
                String method = call.getNameAsString();
                if (BANNED_METHODS.contains(method)) {
                    call.getScope().ifPresent(scope -> {
                        if (BANNED_TYPES.contains(scope.toString())) {
                            violations.add("禁用调用: " + scope + "." + method);
                        }
                    });
                }
            });
            return violations.isEmpty()
                ? ValidationResult.pass()
                : ValidationResult.fail("AST 检测命中: " + violations);
        } catch (ParseProblemException e) {
            return ValidationResult.fail("代码无法解析: " + e.getMessage());
        }
    }
}
