package com.example.paymentcore.payment;

/** 支付单对外投影；status 只表达收款结果，不代表已记账或可结算。 */
public record PaymentView(String paymentId, String merchantOrderId, long amountMinor, String currency,
                          String status, String postingStatus, int version, String attemptStatus) {
}
