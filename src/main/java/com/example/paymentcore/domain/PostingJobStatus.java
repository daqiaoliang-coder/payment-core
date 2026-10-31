package com.example.paymentcore.domain;

/** 支付成功后的账务任务状态；LEDGER_UNAVAILABLE 不改变支付成功事实。 */
public enum PostingJobStatus {
    PENDING,
    POSTED,
    LEDGER_UNAVAILABLE
}
