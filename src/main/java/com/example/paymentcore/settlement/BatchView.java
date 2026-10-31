package com.example.paymentcore.settlement;

/** 批次与清分明细的对外投影。 */
public record BatchView(String batchId, String merchantId, String currency, String ruleVersion, String status,
                        long grossMinor, long refundMinor, long feeMinor, long netMinor, int itemCount,
                        int version) {
}
