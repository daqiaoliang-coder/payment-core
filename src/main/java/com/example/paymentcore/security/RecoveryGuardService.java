package com.example.paymentcore.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 恢复门禁：灾备恢复或去重证据缺失时，先暂停受影响的重放任务；
 * 暂停只阻止本地再次执行，不把已存在的外部操作改判为失败。
 */
@Service
public class RecoveryGuardService {

    /** 支付发送与查单恢复共用的门禁键。 */
    public static final String PAYMENT_RECOVERY = "payment-recovery";

    public record GuardView(String guardKey, boolean paused, String reason, String updatedBy, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public RecoveryGuardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GuardView pause(String guardKey, String reason, String actor) {
        jdbc.update("MERGE INTO recovery_guard KEY(guard_key) VALUES (?, TRUE, ?, ?, CURRENT_TIMESTAMP)",
                guardKey, reason, actor);
        return state(guardKey);
    }

    public GuardView resume(String guardKey, String actor) {
        jdbc.update("MERGE INTO recovery_guard KEY(guard_key) VALUES (?, FALSE, ?, ?, CURRENT_TIMESTAMP)",
                guardKey, "resumed", actor);
        return state(guardKey);
    }

    public boolean isPaused(String guardKey) {
        List<Boolean> rows = jdbc.queryForList("SELECT paused FROM recovery_guard WHERE guard_key = ?",
                Boolean.class, guardKey);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    public GuardView state(String guardKey) {
        List<GuardView> rows = jdbc.query("SELECT guard_key, paused, reason, updated_by, updated_at "
                        + "FROM recovery_guard WHERE guard_key = ?",
                (rs, i) -> new GuardView(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()),
                guardKey);
        if (rows.isEmpty()) {
            return new GuardView(guardKey, false, null, null, null);
        }
        return rows.get(0);
    }
}
