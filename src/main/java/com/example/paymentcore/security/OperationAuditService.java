package com.example.paymentcore.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 操作审计链：与业务写入同一事务追加，回答谁发起、执行了什么、使用哪个规则版本、
 * 依据什么外部证据、修改了哪个业务投影，以及是否经过审批。
 * 审计事件不替代业务唯一约束；trace_id 只用于串联操作，不能充当资金幂等键。
 */
@Service
public class OperationAuditService {

    private static final int DETAIL_LIMIT = 500;

    public record Entry(String actor, String action, String resourceType, String businessId,
                        String ruleVersion, String evidenceRef, String projection, String approvalId,
                        String outcome, String detail, String traceId) {
    }

    public record AuditView(String auditId, String traceId, String actor, String action, String resourceType,
                            String businessId, String ruleVersion, String evidenceRef, String projection,
                            String approvalId, String outcome, String detail, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final SensitiveDataSanitizer sanitizer;

    public OperationAuditService(JdbcTemplate jdbc, SensitiveDataSanitizer sanitizer) {
        this.jdbc = jdbc;
        this.sanitizer = sanitizer;
    }

    /** 记录一次已执行的业务操作，未提供 traceId 时生成新的关联标识。 */
    public String record(Entry entry) {
        String auditId = UUID.randomUUID().toString();
        String traceId = entry.traceId() == null || entry.traceId().isBlank()
                ? UUID.randomUUID().toString()
                : entry.traceId();
        jdbc.update("INSERT INTO operation_audit(audit_id, trace_id, actor, action, resource_type, "
                        + "business_id, rule_version, evidence_ref, projection, approval_id, outcome, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                auditId, traceId, entry.actor(), entry.action(), entry.resourceType(), entry.businessId(),
                entry.ruleVersion(), entry.evidenceRef(), entry.projection(), entry.approvalId(),
                entry.outcome(), truncate(sanitizer.sanitize(entry.detail())));
        return auditId;
    }

    /** 常见成功操作：默认生成 traceId，结果标记为 ACCEPTED。 */
    public String recordAccepted(String actor, String action, String resourceType, String businessId,
                                 String ruleVersion, String evidenceRef, String projection, String approvalId,
                                 String detail) {
        return record(new Entry(actor, action, resourceType, businessId, ruleVersion, evidenceRef, projection,
                approvalId, SecurityAuditService.OUTCOME_ACCEPTED, detail, null));
    }

    public List<AuditView> findByBusinessId(String businessId) {
        return query("SELECT audit_id, trace_id, actor, action, resource_type, business_id, rule_version, "
                        + "evidence_ref, projection, approval_id, outcome, detail, created_at "
                        + "FROM operation_audit WHERE business_id = ? ORDER BY created_at, audit_id",
                businessId);
    }

    public List<AuditView> findByTraceId(String traceId) {
        return query("SELECT audit_id, trace_id, actor, action, resource_type, business_id, rule_version, "
                        + "evidence_ref, projection, approval_id, outcome, detail, created_at "
                        + "FROM operation_audit WHERE trace_id = ? ORDER BY created_at, audit_id",
                traceId);
    }

    public List<AuditView> findByAction(String action) {
        return query("SELECT audit_id, trace_id, actor, action, resource_type, business_id, rule_version, "
                        + "evidence_ref, projection, approval_id, outcome, detail, created_at "
                        + "FROM operation_audit WHERE action = ? ORDER BY created_at, audit_id",
                action);
    }

    public List<AuditView> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<AuditView> rows = jdbc.query("SELECT audit_id, trace_id, actor, action, resource_type, business_id, "
                        + "rule_version, evidence_ref, projection, approval_id, outcome, detail, created_at "
                        + "FROM operation_audit ORDER BY created_at DESC, audit_id DESC LIMIT " + safeLimit,
                (rs, i) -> view(rs));
        return rows;
    }

    private List<AuditView> query(String sql, String value) {
        return jdbc.query(sql, (rs, i) -> view(rs), value);
    }

    private AuditView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuditView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getString(12), rs.getTimestamp(13).toInstant());
    }

    private String truncate(String value) {
        if (value == null || value.length() <= DETAIL_LIMIT) {
            return value;
        }
        return value.substring(0, DETAIL_LIMIT);
    }
}
