package com.example.paymentcore.recon;

/** 对账报告视图：完整性结论独立于业务差异结论。 */
public record ReportView(String reportId, String provider, String reportType, String status, int expectedPages,
                         int receivedPages, int recordCount, long totalMinor, String currency) {
}
