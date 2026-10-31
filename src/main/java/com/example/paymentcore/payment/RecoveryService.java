package com.example.paymentcore.payment;

import com.example.paymentcore.channel.ChannelPort;
import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.domain.AttemptStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查单恢复：扫描 SUBMITTED / PROCESSING / UNKNOWN，复用原渠道身份查询。
 * 查不到记录时保持 UNKNOWN，绝不据此判定失败或新建尝试。
 */
@Service
public class RecoveryService {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final PaymentRepository repository;
    private final PaymentTransactions transactions;
    private final ChannelPort channel;

    public RecoveryService(JdbcTemplate jdbc, PaymentRepository repository, PaymentTransactions transactions,
                           ChannelPort channel) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.channel = channel;
    }

    public int recoverOpenAttempts() {
        List<String> attemptIds = jdbc.queryForList(
                "SELECT attempt_id FROM channel_attempt WHERE status IN (?, ?, ?) "
                        + "ORDER BY created_at, attempt_id LIMIT " + BATCH_LIMIT,
                String.class, AttemptStatus.SUBMITTED.name(), AttemptStatus.PROCESSING.name(),
                AttemptStatus.UNKNOWN.name());

        int handled = 0;
        for (String attemptId : attemptIds) {
            PaymentRepository.AttemptContext ctx = repository.loadAttemptContext(attemptId);
            if (ctx == null) {
                continue;
            }
            ChannelResult result = channel.query(ctx.providerIdempotencyKey());
            switch (result.outcome()) {
                case SUCCESS -> transactions.confirmSuccess(attemptId, result);
                case PROCESSING -> transactions.markProcessing(attemptId, result.detail());
                case FAILED -> transactions.fail(attemptId, result.detail());
                case NOT_FOUND, TIMEOUT -> transactions.markUnknown(attemptId, result.detail());
            }
            handled++;
        }
        return handled;
    }
}
