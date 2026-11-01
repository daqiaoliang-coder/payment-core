package com.example.paymentcore.domain;

/** 敏感操作审批状态：只有 APPROVED 才能支撑受益人变更。 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
