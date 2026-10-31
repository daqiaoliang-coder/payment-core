package com.example.paymentcore.recon;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** 对账报告导入请求：范围、文件身份、分页完整性与业务明细一次提交。 */
public record ReconReportRequest(@NotBlank String provider, @NotBlank String reportType,
                                 @NotNull Instant periodStart, @NotNull Instant periodEnd,
                                 @NotBlank String fileDigest, int expectedPages, long declaredTotalMinor,
                                 @NotBlank String currency, @NotNull @Valid List<Page> pages) {

    public record Page(int pageNo, @NotBlank String pageDigest, @NotNull @Valid List<Record> records) {
    }

    public record Record(@NotBlank String recordKey, @NotBlank String recordType, String businessId,
                         long amountMinor) {
    }
}
