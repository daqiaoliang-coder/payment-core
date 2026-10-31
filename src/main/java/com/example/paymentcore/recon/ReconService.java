package com.example.paymentcore.recon;

import com.example.paymentcore.common.ApiNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/** 对账用例编排：报告完整性与逐笔差异分别查询，不能用任一层结论替代其他层。 */
@Service
public class ReconService {

    private final ReconRepository repository;
    private final ReconTransactions transactions;

    public ReconService(ReconRepository repository, ReconTransactions transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    public ReportView importReport(ReconReportRequest request) {
        return transactions.importReport(request);
    }

    public ReportView getReport(String reportId) {
        ReconRepository.ReportRow row = repository.findReport(reportId);
        if (row == null) {
            throw new ApiNotFoundException("对账报告不存在: " + reportId);
        }
        return ReconRepository.view(row);
    }

    public List<DifferenceView> differences(String reportId) {
        getReport(reportId);
        return repository.differences(reportId);
    }

    public StatementView importStatement(String provider, String bankReference, String direction,
                                         long amountMinor, String currency) {
        if (provider == null || provider.isBlank() || bankReference == null || bankReference.isBlank()) {
            throw new IllegalArgumentException("银行流水的提供方与参考号不能为空");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("银行流水金额必须为正整数");
        }
        return transactions.importStatement(provider, bankReference, direction, amountMinor,
                currency == null || currency.isBlank() ? "CNY" : currency);
    }

    public StatementView allocate(String statementId, String entryId, long amountMinor) {
        return transactions.allocate(statementId, entryId, amountMinor);
    }

    public StatementView getStatement(String statementId) {
        ReconRepository.StatementRow row = repository.findStatement(statementId);
        if (row == null) {
            throw new ApiNotFoundException("银行流水不存在: " + statementId);
        }
        return ReconRepository.view(row);
    }
}
