package com.example.paymentcore.refund;

import com.example.paymentcore.channel.ChannelPort;
import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.domain.RefundStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 退款查单恢复：扫描 PROCESSING / UNKNOWN，复用原退款渠道身份查询。
 * 查不到记录时保持 UNKNOWN 与占额，绝不据此释放额度或创建第二笔退款。
 */
@Service
public class RefundRecoveryService {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final RefundRepository repository;
    private final RefundTransactions transactions;
    private final ChannelPort channel;

    public RefundRecoveryService(JdbcTemplate jdbc, RefundRepository repository, RefundTransactions transactions,
                                 ChannelPort channel) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.channel = channel;
    }

    public int recoverOpenRefunds() {
        List<String> refundIds = jdbc.queryForList(
                "SELECT refund_id FROM refund_order WHERE status IN (?, ?) ORDER BY created_at, refund_id LIMIT "
                        + BATCH_LIMIT,
                String.class, RefundStatus.PROCESSING.name(), RefundStatus.UNKNOWN.name());

        int handled = 0;
        for (String refundId : refundIds) {
            RefundRepository.SendContext ctx = repository.loadSendContext(refundId);
            if (ctx == null) {
                continue;
            }
            ChannelResult result = channel.queryRefund(ctx.providerIdempotencyKey());
            apply(refundId, result);
            handled++;
        }
        return handled;
    }

    private void apply(String refundId, ChannelResult result) {
        try {
            switch (result.outcome()) {
                case SUCCESS -> transactions.confirmSuccess(refundId, result);
                case FAILED -> transactions.release(refundId, result.detail());
                case NOT_FOUND, TIMEOUT -> transactions.markUnknown(refundId, result.detail());
                case PROCESSING -> {
                    // 仍未终结，保留占额
                }
            }
        } catch (EvidenceConflictException e) {
            // 终态后的相反证据只追加差错记录
        }
    }
}
