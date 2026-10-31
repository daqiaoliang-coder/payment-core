package com.example.paymentcore.payment;

import com.example.paymentcore.channel.ChannelPort;
import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.domain.AttemptStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发送器：先以条件更新领取发送权（CREATED -> SUBMITTED，租约版本 +1），
 * 再在数据库事务之外执行渠道调用；结果一律经 T4 或核查状态落库。
 * 注意：领取提交与真实发送之间仍有崩溃窗口，因此恢复必须覆盖 SUBMITTED。
 */
@Service
public class AttemptSender {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final PaymentRepository repository;
    private final PaymentTransactions transactions;
    private final ChannelPort channel;

    public AttemptSender(JdbcTemplate jdbc, PaymentRepository repository, PaymentTransactions transactions,
                         ChannelPort channel) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.channel = channel;
    }

    public int sendPending() {
        List<String> attemptIds = jdbc.queryForList(
                "SELECT attempt_id FROM channel_attempt WHERE status = ? "
                        + "ORDER BY created_at, attempt_id LIMIT " + BATCH_LIMIT,
                String.class, AttemptStatus.CREATED.name());

        int handled = 0;
        for (String attemptId : attemptIds) {
            int claimed = jdbc.update("UPDATE channel_attempt SET status = ?, lease_version = lease_version + 1, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE attempt_id = ? AND status = ?",
                    AttemptStatus.SUBMITTED.name(), attemptId, AttemptStatus.CREATED.name());
            if (claimed == 0) {
                continue; // 已被其他工作者领取
            }
            handled++;

            PaymentRepository.AttemptContext ctx = repository.loadAttemptContext(attemptId);
            if (ctx == null) {
                continue;
            }
            // 渠道调用不在任何数据库事务内；使用已持久化的渠道幂等键
            ChannelResult result = channel.charge(ctx.providerIdempotencyKey(), ctx.merchantOrderId(),
                    ctx.amountMinor(), ctx.currency());
            apply(attemptId, result);
        }
        return handled;
    }

    private void apply(String attemptId, ChannelResult result) {
        switch (result.outcome()) {
            case SUCCESS -> transactions.confirmSuccess(attemptId, result);
            case PROCESSING -> transactions.markProcessing(attemptId, result.detail());
            case FAILED -> transactions.fail(attemptId, result.detail());
            case TIMEOUT, NOT_FOUND -> transactions.markUnknown(attemptId, result.detail());
        }
    }
}
