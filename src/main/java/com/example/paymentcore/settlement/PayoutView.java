package com.example.paymentcore.settlement;

/** 出款单投影；状态只表达付款执行结果，不代表商户账户已到账。 */
public record PayoutView(String payoutId, String batchId, String merchantId, long amountMinor, String currency,
                         String status, String bankReference, String journalId, int version) {
}
