package com.example.paymentcore.domain;

/** 退款单状态；UNKNOWN 保留预留，不是失败。 */
public enum RefundStatus {
    ACCEPTED,
    PROCESSING,
    UNKNOWN,
    SUCCEEDED,
    FAILED
}
