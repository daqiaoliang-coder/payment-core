package com.example.paymentcore.domain;

/** 出款单状态；成功后资金退回用 RETURNED 追加表达，不把旧成功改成失败。 */
public enum PayoutStatus {
    CREATED,
    PROCESSING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    RETURNED
}
