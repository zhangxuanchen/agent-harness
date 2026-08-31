package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 人工审批门禁。
 * <p>对应书中 Ch16 §16.1.1 —— 需人工确认的发布门禁。
 * <p>在高风险发布或关键指标临界时，强制要求人工确认才能放行，
 * 是发布门禁链中的最后一道人工关卡。
 */
@Component
public class HumanGate {

    private static final Logger log = LoggerFactory.getLogger(HumanGate.class);

    /**
     * 请求人工审批。
     *
     * @param releaseId 发布 ID
     * @param reason    审批原因
     * @return 审批单号
     */
    public String requestApproval(String releaseId, String reason) {
        String ticket = "human-" + System.currentTimeMillis();
        log.info("请求人工审批: ticket={}, release={}, reason={}", ticket, releaseId, reason);
        return ticket;
    }

    /**
     * 检查是否已批准。
     *
     * @param ticket 审批单号
     * @return 已批准返回 true
     */
    public boolean isApproved(String ticket) {
        return false;
    }
}
