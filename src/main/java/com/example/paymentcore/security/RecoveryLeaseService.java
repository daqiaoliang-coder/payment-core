package com.example.paymentcore.security;

import com.example.paymentcore.common.ApiConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 恢复任务租约：同一任务同一时刻只允许一个工作者持有执行权。
 * 租约过期只说明本地执行权失效，不能据此判定外部操作失败；
 * 接替者必须复用原操作身份继续核查。
 */
@Service
public class RecoveryLeaseService {

    /** 支付恢复任务名；监控与恢复执行共用同一标识。 */
    public static final String PAYMENT_RECOVERY_TASK = "payment-recovery";

    public record Lease(String taskName, String owner, String token) {
    }

    private final JdbcTemplate jdbc;

    public RecoveryLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 在租约过期或本人持有时领取；其他工作者持有时返回 null，不抢占。 */
    public Lease acquire(String taskName, String owner, long ttlMillis) {
        String token = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expiresAt = Timestamp.from(Instant.now().plusMillis(ttlMillis));
        int updated = jdbc.update("UPDATE recovery_lease SET lease_owner = ?, lease_token = ?, expires_at = ?, "
                        + "version = version + 1 "
                        + "WHERE task_name = ? AND (expires_at <= ? OR lease_owner = ?)",
                owner, token, expiresAt, taskName, now, owner);
        if (updated > 0) {
            return new Lease(taskName, owner, token);
        }
        try {
            jdbc.update("INSERT INTO recovery_lease(task_name, lease_owner, lease_token, expires_at, version) "
                    + "VALUES (?, ?, ?, ?, 1)", taskName, owner, token, expiresAt);
            return new Lease(taskName, owner, token);
        } catch (DuplicateKeyException e) {
            return null; // 其他工作者已取得租约
        }
    }

    public boolean release(String taskName, String token) {
        return jdbc.update("UPDATE recovery_lease SET expires_at = ? "
                        + "WHERE task_name = ? AND lease_token = ?",
                Timestamp.from(Instant.now()), taskName, token) > 0;
    }

    public boolean isHeld(String taskName, String token) {
        List<String> tokens = jdbc.queryForList("SELECT lease_token FROM recovery_lease "
                        + "WHERE task_name = ? AND expires_at > ?",
                String.class, taskName, Timestamp.from(Instant.now()));
        return !tokens.isEmpty() && tokens.get(0).equals(token);
    }

    public void assertHeld(String taskName, String token) {
        if (!isHeld(taskName, token)) {
            throw new ApiConflictException("lease_lost", "恢复任务租约已失效，旧工作者不得继续执行");
        }
    }
}
