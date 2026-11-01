package com.example.paymentcore.security;

import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.common.ApiNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 受益人账户变更：属于敏感操作，必须引用已通过的审批单。
 * 拒绝原因写入安全审计，便于追查谁在缺少授权时尝试修改收款账户。
 */
@Service
public class BeneficiaryService {

    public static final String OPERATION = "CHANGE_BENEFICIARY";

    public record BeneficiaryView(String merchantId, String bankAccountRef, String updatedBy, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final SecurityAuditService audit;

    public BeneficiaryService(JdbcTemplate jdbc, ApprovalService approvals, SecurityAuditService audit) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
    }

    public BeneficiaryView change(String merchantId, String bankAccountRef, String requestedBy,
                                  String approvalId) {
        try {
            approvals.requireApproved(OPERATION, merchantId, approvalId);
        } catch (ApiForbiddenException e) {
            audit.record(requestedBy, OPERATION, merchantId, SecurityAuditService.OUTCOME_REJECTED,
                    "approvalId=" + approvalId + "; " + e.getMessage());
            throw e;
        }
        jdbc.update("MERGE INTO beneficiary_account KEY(merchant_id) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                merchantId, bankAccountRef, requestedBy);
        audit.record(requestedBy, OPERATION, merchantId, SecurityAuditService.OUTCOME_ACCEPTED,
                "bankAccountRef=" + bankAccountRef);
        return get(merchantId);
    }

    public BeneficiaryView get(String merchantId) {
        List<BeneficiaryView> rows = jdbc.query("SELECT merchant_id, bank_account_ref, updated_by, updated_at "
                        + "FROM beneficiary_account WHERE merchant_id = ?",
                (rs, i) -> new BeneficiaryView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()),
                merchantId);
        if (rows.isEmpty()) {
            throw new ApiNotFoundException("受益人账户不存在: " + merchantId);
        }
        return rows.get(0);
    }
}
