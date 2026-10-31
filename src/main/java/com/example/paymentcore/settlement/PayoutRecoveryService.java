package com.example.paymentcore.settlement;

import com.example.paymentcore.bank.MockBank;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.domain.PayoutStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 出款查单恢复：扫描 PROCESSING / UNKNOWN，复用原银行幂等键查询。
 * 查不到记录时保持 UNKNOWN 与占额，绝不据此释放或换单重发。
 */
@Service
public class PayoutRecoveryService {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final SettlementRepository repository;
    private final PayoutTransactions transactions;
    private final MockBank bank;

    public PayoutRecoveryService(JdbcTemplate jdbc, SettlementRepository repository,
                                 PayoutTransactions transactions, MockBank bank) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.bank = bank;
    }

    public int recoverOpenPayouts() {
        List<String> payoutIds = jdbc.queryForList(
                "SELECT payout_id FROM payout_order WHERE status IN (?, ?) ORDER BY created_at, payout_id LIMIT "
                        + BATCH_LIMIT,
                String.class, PayoutStatus.PROCESSING.name(), PayoutStatus.UNKNOWN.name());

        int handled = 0;
        for (String payoutId : payoutIds) {
            SettlementRepository.PayoutContext ctx = repository.loadPayoutContext(payoutId);
            if (ctx == null) {
                continue;
            }
            MockBank.BankResult result = bank.query(ctx.providerIdempotencyKey());
            apply(payoutId, result);
            handled++;
        }
        return handled;
    }

    private void apply(String payoutId, MockBank.BankResult result) {
        try {
            switch (result.outcome()) {
                case SUCCESS -> transactions.confirmSuccess(payoutId, result);
                case FAILED -> transactions.release(payoutId, result.detail());
                case NOT_FOUND, TIMEOUT -> transactions.markUnknown(payoutId, result.detail());
                case PROCESSING -> {
                    // 仍未终结，保留占额
                }
            }
        } catch (EvidenceConflictException e) {
            // 终态后的相反证据只追加差错记录
        }
    }
}
