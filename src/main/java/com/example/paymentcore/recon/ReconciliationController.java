package com.example.paymentcore.recon;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 对账入口：报告导入与银行流水分配。 */
@RestController
@RequestMapping("/api/recon")
public class ReconciliationController {

    public record StatementRequest(@NotBlank String provider, @NotBlank String bankReference,
                                    @NotBlank String direction, @NotNull Long amountMinor,
                                    @NotBlank String currency) {
    }

    public record AllocationRequest(@NotBlank String entryId, @NotNull Long amountMinor) {
    }

    private final ReconService service;

    public ReconciliationController(ReconService service) {
        this.service = service;
    }

    @PostMapping("/reports")
    public ReportView importReport(@Valid @RequestBody ReconReportRequest request) {
        return service.importReport(request);
    }

    @GetMapping("/reports/{reportId}")
    public ReportView report(@PathVariable String reportId) {
        return service.getReport(reportId);
    }

    @GetMapping("/reports/{reportId}/differences")
    public List<DifferenceView> differences(@PathVariable String reportId) {
        return service.differences(reportId);
    }

    @PostMapping("/bank-statements")
    public StatementView importStatement(@Valid @RequestBody StatementRequest request) {
        return service.importStatement(request.provider(), request.bankReference(), request.direction(),
                request.amountMinor(), request.currency());
    }

    @PostMapping("/bank-statements/{statementId}/allocations")
    public StatementView allocate(@PathVariable String statementId,
                                  @Valid @RequestBody AllocationRequest request) {
        return service.allocate(statementId, request.entryId(), request.amountMinor());
    }

    @GetMapping("/bank-statements/{statementId}")
    public StatementView statement(@PathVariable String statementId) {
        return service.getStatement(statementId);
    }
}
