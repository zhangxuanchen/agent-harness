package io.etclovg.codepilot.data;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Text-to-SQL 安全守护。
 * <p>对应书中 Ch13 §13.1 —— SQL 引擎的三道安全防线。
 * <p>串联 Schema 白名单 → 改写检测 → 只读执行三道防线，
 * 防 {@code DROP TABLE} / {@code UNION SELECT} / 敏感字段泄露等注入与越权。
 *
 * <p><b>概念示例</b>：实际部署需配合数据库视图层权限（Schema 白名单已在视图层屏蔽）。
 * 本类为概念骨架，方法签名对齐章节调用；防线 2（正则检测）为可运行实现，
 * 防线 3（只读事务 + 参数绑定）为桩——需配置真实 DataSource 后取消注释 {@code jdbc.execute} 段。
 */
@Component
public class SqlSafetyGuard {

    private static final Set<Pattern> DANGEROUS_PATTERNS = Set.of(
        Pattern.compile("UNION\\s+SELECT", Pattern.CASE_INSENSITIVE),
        Pattern.compile("INTO\\s+OUTFILE", Pattern.CASE_INSENSITIVE),
        Pattern.compile("DROP\\s+TABLE", Pattern.CASE_INSENSITIVE),
        Pattern.compile("DELETE\\s+FROM", Pattern.CASE_INSENSITIVE),
        Pattern.compile("UPDATE\\s+\\w+\\s+SET", Pattern.CASE_INSENSITIVE)
    );
    private static final int MAX_ROWS = 1000;

    /**
     * 安全执行 SQL。
     * <p>防线 2：查询改写攻击检测（防线 1 的 Schema 白名单已在视图层屏蔽）；
     * 防线 3：只读事务 + 行数限制。
     *
     * @param sql    SQL 语句
     * @param params 参数列表
     * @param jdbc   JdbcTemplate（签名对齐章节代码；桩实现不实际执行 SQL）
     * @return 安全执行结果
     */
    public SqlResult executeSafely(String sql, List<Object> params, JdbcTemplate jdbc) {
        // 防线 2：查询改写攻击检测（防线 1 的 Schema 白名单已在视图层屏蔽）
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(sql).find()) {
                return SqlResult.blocked("危险模式命中");
            }
        }
        // 防线 3：只读事务 + 行数限制
        // 概念示例：实际应通过 jdbc.execute(ConnectionCallback) 强制只读并参数绑定：
        //   String limitedSql = sql + " LIMIT " + MAX_ROWS;
        //   return jdbc.execute((ConnectionCallback<SqlResult>) con -> {
        //       con.setReadOnly(true);
        //       try (PreparedStatement ps = con.prepareStatement(limitedSql)) {
        //           for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
        //           return SqlResult.of(ps.executeQuery());
        //       }
        //   });
        // 此处为桩实现，直接返回带 LIMIT 的 SQL 占位，不实际执行。
        String limitedSql = sql + " LIMIT " + MAX_ROWS;
        return SqlResult.of(limitedSql);
    }

    /**
     * SQL 执行结果。
     */
    public record SqlResult(boolean blocked, String reason, Object payload) {

        /**
         * 构造被拦截的结果。
         *
         * @param reason 拦截原因
         * @return 被拦截结果
         */
        public static SqlResult blocked(String reason) {
            return new SqlResult(true, reason, null);
        }

        /**
         * 构造正常执行的结果。
         *
         * @param payload 执行结果
         * @return 正常结果
         */
        public static SqlResult of(Object payload) {
            return new SqlResult(false, null, payload);
        }
    }
}
