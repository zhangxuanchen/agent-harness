package io.etclovg.codepilot.tools;

import java.time.Instant;
import java.util.List;

/**
 * 删除操作结果记录。
 * <p>对应书中 Ch05 §5.4 —— 文件/资源删除工具的返回结构。
 * <p>描述批量删除的成败、受影响条目与跳过条目，供审计与回滚参考。
 *
 * @param target      删除目标描述（路径或资源 ID）
 * @param deleted     已成功删除的条目列表
 * @param skipped     被跳过的条目列表（如受保护资源）
 * @param success     是否整体成功
 * @param message     结果描述
 * @param completedAt 完成时间
 */
public record DeleteResult(
        String target,
        List<String> deleted,
        List<String> skipped,
        boolean success,
        String message,
        Instant completedAt
) {

    /**
     * 构造成功结果。
     *
     * @param target  删除目标
     * @param deleted 已删除条目
     * @return 成功结果
     */
    public static DeleteResult success(String target, List<String> deleted) {
        return new DeleteResult(target, deleted, List.of(), true, "删除成功", Instant.now());
    }

    /**
     * 构造部分成功结果。
     *
     * @param target  删除目标
     * @param deleted 已删除条目
     * @param skipped 跳过条目
     * @return 部分成功结果
     */
    public static DeleteResult partial(String target, List<String> deleted, List<String> skipped) {
        return new DeleteResult(target, deleted, skipped, false, "部分条目被跳过", Instant.now());
    }

    /**
     * 已删除条目数量。
     *
     * @return 数量
     */
    public int deletedCount() {
        return deleted == null ? 0 : deleted.size();
    }
}
