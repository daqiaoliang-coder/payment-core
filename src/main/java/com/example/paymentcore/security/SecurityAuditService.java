package com.example.paymentcore.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 安全审计：敏感操作无论成功或拒绝都留痕；明细必须先脱敏再落库，
 * 避免卡号、密钥或令牌通过审计查询重新暴露。
 */
@Service
public class SecurityAuditService {

    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_RECORDED = "RECORDED";

    private static final int DETAIL_LIMIT = 500;

    public record AuditView(String auditId, String actor, String action, String targetId, String outcome,
                            String detail, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final SensitiveDataSanitizer sanitizer;

    public SecurityAuditService(JdbcTemplate jdbc, SensitiveDataSanitizer sanitizer) {
        this.jdbc = jdbc;
        this.sanitizer = sanitizer;
    }

    public String record(String actor, String action, String targetId, String outcome, String detail) {
        String auditId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO security_audit(audit_id, actor, action, target_id, outcome, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                auditId, actor, action, targetId, outcome, truncate(sanitizer.sanitize(detail)));
        return auditId;
    }

    public List<AuditView> findByTarget(String targetId) {
        return jdbc.query("SELECT audit_id, actor, action, target_id, outcome, detail, created_at "
                        + "FROM security_audit WHERE target_id = ? ORDER BY created_at, audit_id",
                (rs, i) -> new AuditView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()),
                targetId);
    }

    public List<AuditView> findByAction(String action) {
        return jdbc.query("SELECT audit_id, actor, action, target_id, outcome, detail, created_at "
                        + "FROM security_audit WHERE action = ? ORDER BY created_at, audit_id",
                (rs, i) -> new AuditView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()),
                action);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= DETAIL_LIMIT) {
            return value;
        }
        return value.substring(0, DETAIL_LIMIT);
    }
}
