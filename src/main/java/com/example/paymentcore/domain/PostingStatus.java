package com.example.paymentcore.domain;

/** 记账状态占位；阶段 1 不实现账务，仅保留事实与记账诉求的分离。 */
public enum PostingStatus {
    PENDING_POSTING,
    POSTED
}
