package com.example.paymentcore.domain;

/** 模拟银行场景，仅用于验证出款超时、未知与退回。 */
public enum BankScenario {
    SUCCESS,
    TIMEOUT_AFTER_DEBIT,
    DECLINE,
    PROCESSING_THEN_SUCCESS
}
