package com.example.paymentcore.domain;

/** 支付单投影状态，仅表达收款结果，不表达记账或结算资格。 */
public enum PaymentStatus {
    ACCEPTED,
    SUCCEEDED,
    FAILED
}
