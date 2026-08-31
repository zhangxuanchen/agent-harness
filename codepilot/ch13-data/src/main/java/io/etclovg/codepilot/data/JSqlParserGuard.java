package io.etclovg.codepilot.data;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.JSQLParserException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Text-to-SQL 安全守护（AST 级升级版）。
 * <p>对应书中 Ch13 §13.1 —— SQL 引擎三道防线的 AST 级升级，
 * 用 JSqlParser 的 AST 解析替换 {@link SqlSafetyGuard} 的正则匹配，抗绕过能力提升一个数量级。
 *
 * <p>防线映射：
 * <ul>
 *   <li>防线 2（查询改写检测）：从字符级正则升级为 AST 级语句类型校验——
 *       {@code CCJSqlParserUtil.parse(sql)} 解析后，非 {@code Select} 语句直接拦截，
 *       天然抗空格/注释/大小写变体绕过。本类为可运行实现（依赖 jsqlparser 4.9）。</li>
 *   <li>防线 1（Schema 白名单）：升级路径为 Apache Calcite（需注册 Schema 元数据，
 *       {@code validator.validate(sqlNode)} 在 SQL 到达 JDBC 前完成列级访问审查），
 *       本类未集成 Calcite，仅做 JSqlParser 升级。</li>
 *   <li>防线 3（只读事务 + 行数限制）：与 {@link SqlSafetyGuard} 一致，签名对齐但桩不实际执行。</li>
 * </ul>
 *
 * <p>与 {@link SqlSafetyGuard} 是"正则版→AST 版"的升级关系，非替代——
 * 生产环境可按规模选择：冷启动/中小项目用正则版，上规模或需精确拦截时升 AST 版。
 */
@Component
public class JSqlParserGuard {

    private static final int MAX_ROWS = 1000;

    /**
     * 安全执行 SQL（AST 级校验）。
     * <p>防线 2：AST 级语句类型校验（{@code instanceof Select}）；
     * 防线 3：只读事务 + 行数限制。
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @param jdbc   JdbcTemplate（签名对齐章节代码；桩实现不实际执行 SQL）
     * @return 安全执行结果（复用 {@link SqlSafetyGuard.SqlResult}）
     */
    public SqlSafetyGuard.SqlResult executeSafely(String sql, List<Object> params, JdbcTemplate jdbc) {
        try {
            // 防线 2：AST 级语句类型校验（天然抗绕过）
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                return SqlSafetyGuard.SqlResult.blocked("非 SELECT 语句被拦截：" + stmt.getClass().getSimpleName());
            }
            // AST 级：拦截 UNION SELECT、SELECT INTO 等危险结构（扩展点：遍历 PlainSelect.getSetOperationList）
            Select select = (Select) stmt;

            // 防线 3：只读事务 + 行数限制
            // 概念示例：实际应通过 jdbc.execute(ConnectionCallback) 强制只读并参数绑定：
            //   String limitedSql = sql + " LIMIT " + MAX_ROWS;
            //   return jdbc.execute((ConnectionCallback<SqlSafetyGuard.SqlResult>) con -> {
            //       con.setReadOnly(true);
            //       try (PreparedStatement ps = con.prepareStatement(limitedSql)) {
            //           for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            //           return SqlSafetyGuard.SqlResult.of(ps.executeQuery());
            //       }
            //   });
            // 此处为桩实现，直接返回带 LIMIT 的 SQL 占位，不实际执行。
            String limitedSql = sql + " LIMIT " + MAX_ROWS;
            return SqlSafetyGuard.SqlResult.of(limitedSql);
        } catch (JSQLParserException e) {
            return SqlSafetyGuard.SqlResult.blocked("SQL 语法校验未通过：" + e.getMessage());
        }
    }
}
