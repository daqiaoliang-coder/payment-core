package com.example.paymentcore.security;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.common.ApiNotFoundException;
import com.example.paymentcore.domain.ApprovalStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 敏感操作审批：受益人变更等动作必须引用针对同一目标的有效批准。
 * 缺少审批时拒绝并留痕，不能把“有审批号”当作“审批已通过”。
 */
@Service
public class ApprovalService {

    public record ApprovalView(String approvalId, String operation, String targetId, String requestedBy,
                               String approvedBy, String status, String reason, Instant createdAt,
                               Instant decidedAt) {
    }

    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    public ApprovalService(JdbcTemplate jdbc, SecurityAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public ApprovalView request(String operation, String targetId, String requestedBy, String reason) {
        String approvalId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO approval_request(approval_id, operation, target_id, requested_by, status, reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                approvalId, operation, targetId, requestedBy, ApprovalStatus.PENDING.name(), reason);
        audit.record(requestedBy, "APPROVAL_REQUESTED", targetId, SecurityAuditService.OUTCOME_ACCEPTED,
                "operation=" + operation + "; reason=" + reason);
        return get(approvalId);
    }

    public ApprovalView approve(String approvalId, String approvedBy, String reason) {
        ApprovalView existing = get(approvalId);
        int updated = jdbc.update("UPDATE approval_request SET status = ?, approved_by = ?, reason = ?, "
                        + "decided_at = CURRENT_TIMESTAMP WHERE approval_id = ? AND status = ?",
                ApprovalStatus.APPROVED.name(), approvedBy, reason, approvalId,
                ApprovalStatus.PENDING.name());
        if (updated == 0) {
            throw new ApiConflictException("approval_not_pending",
                    "审批单不处于待审批状态: " + existing.status());
        }
        audit.record(approvedBy, "APPROVAL_GRANTED", existing.targetId(),
                SecurityAuditService.OUTCOME_ACCEPTED,
                "approvalId=" + approvalId + "; operation=" + existing.operation() + "; reason=" + reason);
        return get(approvalId);
    }

    public ApprovalView get(String approvalId) {
        List<ApprovalView> rows = jdbc.query("SELECT approval_id, operation, target_id, requested_by, "
                        + "approved_by, status, reason, created_at, decided_at "
                        + "FROM approval_request WHERE approval_id = ?",
                (rs, i) -> new ApprovalView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()),
                approvalId);
        if (rows.isEmpty()) {
            throw new ApiNotFoundException("审批单不存在: " + approvalId);
        }
        return rows.get(0);
    }

    /** 校验审批 operation、目标与状态；任何一项不满足都按越权拒绝。 */
    public ApprovalView requireApproved(String operation, String targetId, String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            throw new ApiForbiddenException("敏感操作缺少审批单: " + operation);
        }
        List<ApprovalView> rows = jdbc.query("SELECT approval_id, operation, target_id, requested_by, "
                        + "approved_by, status, reason, created_at, decided_at "
                        + "FROM approval_request WHERE approval_id = ?",
                (rs, i) -> new ApprovalView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()),
                approvalId);
        if (rows.isEmpty()) {
            throw new ApiForbiddenException("审批单不存在，拒绝敏感操作: " + approvalId);
        }
        ApprovalView approval = rows.get(0);
        if (!operation.equals(approval.operation()) || !targetId.equals(approval.targetId())
                || !ApprovalStatus.APPROVED.name().equals(approval.status())) {
            throw new ApiForbiddenException("审批单未覆盖该操作或尚未通过: " + approvalId);
        }
        return approval;
    }
}
