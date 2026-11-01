package com.example.paymentcore.payment;

import com.example.paymentcore.channel.ChannelPort;
import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.domain.AttemptStatus;
import com.example.paymentcore.security.RecoveryGuardService;
import com.example.paymentcore.security.RecoveryLeaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查单恢复：扫描 SUBMITTED / PROCESSING / UNKNOWN，复用原渠道身份查询。
 * 查不到记录时保持 UNKNOWN，绝不据此判定失败或新建尝试。
 * 恢复任务先领取租约；租约失效只能停止本地继续执行，不能证明外部操作失败。
 */
@Service
public class RecoveryService {

    private static final int BATCH_LIMIT = 50;
    private static final String TASK_NAME = RecoveryLeaseService.PAYMENT_RECOVERY_TASK;
    private static final String OWNER = "payment-recovery-worker";
    private static final long LEASE_TTL_MILLIS = 30_000L;

    private final JdbcTemplate jdbc;
    private final PaymentRepository repository;
    private final PaymentTransactions transactions;
    private final ChannelPort channel;
    private final RecoveryLeaseService leases;
    private final RecoveryGuardService guard;

    public RecoveryService(JdbcTemplate jdbc, PaymentRepository repository, PaymentTransactions transactions,
                           ChannelPort channel, RecoveryLeaseService leases, RecoveryGuardService guard) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transactions = transactions;
        this.channel = channel;
        this.leases = leases;
        this.guard = guard;
    }

    public int recoverOpenAttempts() {
        if (guard.isPaused(RecoveryGuardService.PAYMENT_RECOVERY)) {
            return 0; // 暂停受影响重放，补齐去重证据前不开放重复执行
        }
        RecoveryLeaseService.Lease lease = leases.acquire(TASK_NAME, OWNER, LEASE_TTL_MILLIS);
        if (lease == null) {
            return 0; // 其他工作者持有恢复执行权
        }
        try {
            List<String> attemptIds = jdbc.queryForList(
                    "SELECT attempt_id FROM channel_attempt WHERE status IN (?, ?, ?) "
                            + "ORDER BY created_at, attempt_id LIMIT " + BATCH_LIMIT,
                    String.class, AttemptStatus.SUBMITTED.name(), AttemptStatus.PROCESSING.name(),
                    AttemptStatus.UNKNOWN.name());

            int handled = 0;
            for (String attemptId : attemptIds) {
                if (!leases.isHeld(TASK_NAME, lease.token())) {
                    break; // 租约已被接替：旧工作者必须停止，不能继续制造外部效果
                }
                PaymentRepository.AttemptContext ctx = repository.loadAttemptContext(attemptId);
                if (ctx == null) {
                    continue;
                }
                // 复用原渠道幂等键查询，不因租约变化新建尝试或更换渠道身份
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
        } finally {
            leases.release(TASK_NAME, lease.token());
        }
    }
}
