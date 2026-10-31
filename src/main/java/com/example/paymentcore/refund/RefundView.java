package com.example.paymentcore.refund;

/** 退款对外投影；status 独立于原支付成功状态。 */
public record RefundView(String refundId, String paymentId, String merchantId, long amountMinor,
                         String currency, String status, int version) {
}
