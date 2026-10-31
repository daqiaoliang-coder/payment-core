package com.example.paymentcore.recon;

/** 逐笔差异：汇总相等不能关闭逐笔差错。 */
public record DifferenceView(String differenceId, String reportId, String recordKey, String differenceType,
                             long amountMinor, String currency, String status, String detail) {
}
