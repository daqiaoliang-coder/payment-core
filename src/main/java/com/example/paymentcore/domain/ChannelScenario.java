package com.example.paymentcore.domain;

/** 模拟渠道场景，仅用于本地验证超时、处理中与失败分支。 */
public enum ChannelScenario {
    /** 正常扣款成功 */
    SUCCESS,
    /** 渠道已扣款但响应超时：本地进入 UNKNOWN，查单可找回成功 */
    CHARGE_THEN_TIMEOUT,
    /** 渠道先返回处理中，查单后确认成功 */
    PROCESSING_THEN_SUCCESS,
    /** 渠道明确拒绝，未产生资金效果 */
    DECLINE
}
