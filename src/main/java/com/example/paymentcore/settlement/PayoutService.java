package com.example.paymentcore.settlement;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiNotFoundException;
import org.springframework.stereotype.Service;

/** 出款用例编排：同一批次只对应一个出款单，重复创建返回原单，不换外部意图身份。 */
@Service
public class PayoutService {

    private final SettlementRepository repository;
    private final PayoutTransactions transactions;

    public PayoutService(SettlementRepository repository, PayoutTransactions transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    public PayoutView createPayout(String batchId) {
        SettlementRepository.PayoutRow existing = repository.findPayoutByBatch(batchId);
        if (existing != null) {
            return SettlementRepository.payoutView(existing);
        }
        try {
            return transactions.create(batchId);
        } catch (ApiConflictException e) {
            if (!"payout_already_exists".equals(e.code())) {
                throw e;
            }
            SettlementRepository.PayoutRow winner = repository.findPayoutByBatch(batchId);
            if (winner == null) {
                throw e;
            }
            return SettlementRepository.payoutView(winner);
        }
    }

    public PayoutView getPayout(String payoutId) {
        SettlementRepository.PayoutRow row = repository.findPayout(payoutId);
        if (row == null) {
            throw new ApiNotFoundException("出款单不存在: " + payoutId);
        }
        return SettlementRepository.payoutView(row);
    }

    public PayoutView markReturned(String payoutId, String reason, String approvedBy) {
        if (reason == null || reason.isBlank() || approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("退回必须记录原因与审批人");
        }
        return transactions.markReturned(payoutId, reason, approvedBy);
    }
}
