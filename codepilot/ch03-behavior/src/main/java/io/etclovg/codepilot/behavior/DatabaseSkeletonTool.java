package io.etclovg.codepilot.behavior;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 数据库骨架工具——CodePilot 初版的 T 层工具（故意留缺口）。
 * <p>对应书中 Ch03 §3.5.1 —— 故意粗糙的 2 个手写 SQL 工具，作为第 5 章结构化描述升级的起点。
 *
 * <p>三个故意缺口（与 {@link CodePilotSkeleton} 的缺口清单对应）：
 * <ol>
 *   <li><b>缺口 2 · 工具描述粗糙</b>：{@code description} 仅一句话，无参数 Schema 五要素，
 *       模型仅凭"执行 SQL"猜测何时调用、参数怎么填。</li>
 *   <li><b>缺口 1 · 无容器隔离</b>：直接"执行" SQL，无沙箱，爆炸半径不受限。</li>
 *   <li><b>缺口 6 · 零安全防护</b>：不校验 {@code DROP TABLE} 等危险操作，可被直接执行。</li>
 * </ol>
 *
 * <p>骨架不真实执行 SQL（避免教学运行时破坏环境），仅返回占位结果。
 * 第 5 章将其升级为带结构化 Schema 描述和参数校验的版本。
 */
@Component
public class DatabaseSkeletonTool {

    /**
     * 执行 SQL 语句。
     * <p>⚠ 缺口 2：描述粗糙，模型不知道能传什么 SQL、返回什么结构。
     * <p>⚠ 缺口 1 + 6：无沙箱、无危险操作拦截，{@code DROP TABLE} 可直接"执行"。
     *
     * @param sql SQL 语句
     * @return 占位结果（骨架不真实执行）
     */
    @Tool(name = "execute_sql", description = "执行 SQL")
    public String executeSql(
            @ToolParam(name = "sql", description = "SQL 语句") String sql) {
        // ⚠ 缺口 1: 无容器隔离，直接在宿主机执行
        // ⚠ 缺口 6: 无危险操作拦截，DROP TABLE 可直接执行
        return "[Skeleton] 执行 SQL: " + sql + " → (骨架不真实执行，返回占位结果)";
    }

    /**
     * 查询表结构。
     * <p>⚠ 缺口 2：描述粗糙，无返回结构说明，模型不知道字段含义。
     *
     * @param table 表名
     * @return 占位的表结构
     */
    @Tool(name = "query_schema", description = "查询表结构")
    public String querySchema(
            @ToolParam(name = "table", description = "表名") String table) {
        return "[Skeleton] 表 " + table + " 结构: (骨架占位) id INT, name VARCHAR, created_at TIMESTAMP";
    }
}
