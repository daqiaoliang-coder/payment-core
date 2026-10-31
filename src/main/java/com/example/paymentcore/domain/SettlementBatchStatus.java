package com.example.paymentcore.domain;

/** 结算批次状态；冻结后成员与规则不可暗改。 */
public enum SettlementBatchStatus {
    OPEN,
    FROZEN,
    PAID,
    RETURNED
}
