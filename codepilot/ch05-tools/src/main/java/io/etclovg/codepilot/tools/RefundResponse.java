package io.etclovg.codepilot.tools;

import java.time.Instant;
import java.util.Map;

/**
 * 退款响应记录。
 * <p>对应书中 Ch05 §5.3 —— 客服工具中退款接口的响应结构。
 * <p>由 {@code CustomerServiceTools} 调用退款 API 后返回，供编排层据此
 * 决定后续话术与是否需要人工审核。
 *
 * @param refundId     退款单号
 * @param orderId      原订单 ID
 * @param amount       退款金额
 * @param currency     币种
 * @param status       退款状态
 * @param processedAt  处理时间
 * @param metadata     附加元数据
 */
public record RefundResponse(
        String refundId,
        String orderId,
        double amount,
        String currency,
        Status status,
        Instant processedAt,
        Map<String, Object> metadata
) {

    /**
     * 构造成功退款响应。
     *
     * @param refundId 退款单号
     * @param orderId  订单 ID
     * @param amount   金额
     * @param currency 币种
     * @return 成功响应
     */
    public static RefundResponse approved(String refundId, String orderId, double amount, String currency) {
        return new RefundResponse(refundId, orderId, amount, currency, Status.APPROVED, Instant.now(), Map.of());
    }

    /**
     * 构造需人工审核的退款响应。
     *
     * @param refundId 退款单号
     * @param orderId  订单 ID
     * @param reason   审核原因
     * @return 待审核响应
     */
    public static RefundResponse pendingReview(String refundId, String orderId, String reason) {
        return new RefundResponse(refundId, orderId, 0, "USD", Status.PENDING_REVIEW, Instant.now(),
                Map.of("reason", reason));
    }

    /**
     * 退款状态枚举。
     */
    public enum Status {
        /** 已批准 */
        APPROVED,
        /** 待人工审核 */
        PENDING_REVIEW,
        /** 已拒绝 */
        REJECTED,
        /** 处理失败 */
        FAILED
    }
}
