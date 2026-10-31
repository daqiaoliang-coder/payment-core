package com.example.paymentcore.settlement;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** 清分与结算用例编排：规则版本在开批次时固定，成员与合计同事务维护。 */
@Service
public class SettlementService {

    private final SettlementRepository repository;
    private final SettlementTransactions transactions;

    public SettlementService(SettlementRepository repository, SettlementTransactions transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    public BatchView openBatch(String merchantId, String currency, String ruleVersion) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId 不能为空");
        }
        String normalized = currency == null || currency.isBlank()
                ? "CNY" : currency.toUpperCase(Locale.ROOT);
        return transactions.openBatch(merchantId, normalized, ruleVersion);
    }

    public SettlementItemView addPayment(String batchId, String paymentId) {
        return transactions.addPayment(batchId, paymentId);
    }

    public SettlementItemView addRefund(String batchId, String refundId) {
        return transactions.addRefund(batchId, refundId);
    }

    public BatchView freeze(String batchId) {
        return transactions.freeze(batchId);
    }

    public BatchView getBatch(String batchId) {
        BatchView view = repository.batchView(batchId);
        if (view == null) {
            throw new ApiNotFoundException("结算批次不存在: " + batchId);
        }
        return view;
    }

    public List<SettlementItemView> items(String batchId) {
        getBatch(batchId);
        return repository.items(batchId);
    }

    public void assertOpen(String batchId) {
        BatchView view = getBatch(batchId);
        if (!"OPEN".equals(view.status())) {
            throw new ApiConflictException("batch_not_open", "批次已冻结");
        }
    }
}
