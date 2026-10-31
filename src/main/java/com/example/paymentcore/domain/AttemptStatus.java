package com.example.paymentcore.domain;

/** 渠道尝试状态；UNKNOWN 是本地知识状态，不是渠道业务事实。 */
public enum AttemptStatus {
    CREATED,
    SUBMITTED,
    PROCESSING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
