package com.example.paymentcore.settlement;

import com.example.paymentcore.bank.MockBank;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.domain.PayoutStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 出款发送器：先以条件更新领取 CREATED，再在事务外调用银行。
 * 调用始终使用已持久化的银行幂等键；超时一律进入 UNKNOWN 并保留占额。
 */
@Service
public class PayoutSender {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final SettlementRepository repository;
    private final PayoutTransactions transactions;
    private final MockBank bank;

    public PayoutSender(JdbcTemplate jdbc, SettlementRepository repository, PayoutTransactions transactions,
                        MockBank bank) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.bank = bank;
    }

    public int sendPending() {
        List<String> payoutIds = jdbc.queryForList(
                "SELECT payout_id FROM payout_order WHERE status = ? ORDER BY created_at, payout_id LIMIT "
                        + BATCH_LIMIT,
                String.class, PayoutStatus.CREATED.name());

        int handled = 0;
        for (String payoutId : payoutIds) {
            int claimed = jdbc.update("UPDATE payout_order SET status = ?, version = version + 1, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE payout_id = ? AND status = ?",
                    PayoutStatus.PROCESSING.name(), payoutId, PayoutStatus.CREATED.name());
            if (claimed == 0) {
                continue;
            }
            handled++;
            SettlementRepository.PayoutContext ctx = repository.loadPayoutContext(payoutId);
            if (ctx == null) {
                continue;
            }
            MockBank.BankResult result = bank.pay(ctx.providerIdempotencyKey(), ctx.merchantId(),
                    ctx.amountMinor(), ctx.currency());
            apply(payoutId, result);
        }
        return handled;
    }

    private void apply(String payoutId, MockBank.BankResult result) {
        try {
            switch (result.outcome()) {
                case SUCCESS -> transactions.confirmSuccess(payoutId, result);
                case FAILED -> transactions.release(payoutId, result.detail());
                case TIMEOUT, NOT_FOUND -> transactions.markUnknown(payoutId, result.detail());
                case PROCESSING -> {
                    // 保持 PROCESSING，等待查单恢复
                }
            }
        } catch (EvidenceConflictException e) {
            // 终态后的相反证据只追加差错记录，不阻断后续任务
        }
    }
}
