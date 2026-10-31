package com.example.paymentcore.refund;

import com.example.paymentcore.channel.ChannelPort;
import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.domain.RefundStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 退款发送器：先以条件更新把 ACCEPTED 领取为 PROCESSING，再在事务外调用渠道。
 * 渠道调用始终使用已持久化的退款渠道幂等键，超时一律进入 UNKNOWN 并保留占额。
 */
@Service
public class RefundSender {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final RefundRepository repository;
    private final RefundTransactions transactions;
    private final ChannelPort channel;

    public RefundSender(JdbcTemplate jdbc, RefundRepository repository, RefundTransactions transactions,
                        ChannelPort channel) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.channel = channel;
    }

    public int sendPending() {
        List<String> refundIds = jdbc.queryForList(
                "SELECT refund_id FROM refund_order WHERE status = ? ORDER BY created_at, refund_id LIMIT "
                        + BATCH_LIMIT,
                String.class, RefundStatus.ACCEPTED.name());

        int handled = 0;
        for (String refundId : refundIds) {
            int claimed = jdbc.update("UPDATE refund_order SET status = ?, version = version + 1, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE refund_id = ? AND status = ?",
                    RefundStatus.PROCESSING.name(), refundId, RefundStatus.ACCEPTED.name());
            if (claimed == 0) {
                continue;
            }
            handled++;
            RefundRepository.SendContext ctx = repository.loadSendContext(refundId);
            if (ctx == null) {
                continue;
            }
            ChannelResult result = channel.refund(ctx.providerIdempotencyKey(), ctx.merchantOrderId(),
                    ctx.amountMinor(), ctx.currency());
            apply(refundId, result);
        }
        return handled;
    }

    private void apply(String refundId, ChannelResult result) {
        try {
            switch (result.outcome()) {
                case SUCCESS -> transactions.confirmSuccess(refundId, result);
                case FAILED -> transactions.release(refundId, result.detail());
                case TIMEOUT, NOT_FOUND -> transactions.markUnknown(refundId, result.detail());
                case PROCESSING -> {
                    // 已在 PROCESSING，等待查单恢复
                }
            }
        } catch (EvidenceConflictException e) {
            // 终态后的相反证据只追加差错记录，不阻断后续任务
        }
    }
}
