package com.example.paymentcore;

import com.example.paymentcore.channel.MockChannel;
import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.domain.ChannelScenario;
import com.example.paymentcore.payment.AttemptSender;
import com.example.paymentcore.payment.PaymentService;
import com.example.paymentcore.payment.PaymentView;
import com.example.paymentcore.payment.RecoveryService;
import com.example.paymentcore.payment.TickService;
import com.example.paymentcore.security.ApprovalService;
import com.example.paymentcore.security.AuthorityService;
import com.example.paymentcore.security.BeneficiaryService;
import com.example.paymentcore.security.RecoveryGuardService;
import com.example.paymentcore.security.RecoveryLeaseService;
import com.example.paymentcore.security.SecurityAuditService;
import com.example.paymentcore.security.SensitiveDataSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4 适用断言：R01—R05。
 * 仅验证本地租约、门禁、地域权威、审批与脱敏骨架；
 * 容量压测、灾备演练与真实安全审计尚未执行。
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityStage4Tests {

    private static final String MERCHANT = "M-STAGE4";
    private static final String RECOVERY_TASK = "payment-recovery";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private AttemptSender sender;
    @Autowired
    private RecoveryService recovery;
    @Autowired
    private TickService tickService;
    @Autowired
    private MockChannel mockChannel;
    @Autowired
    private RecoveryLeaseService leases;
    @Autowired
    private RecoveryGuardService guard;
    @Autowired
    private AuthorityService authority;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private BeneficiaryService beneficiaries;
    @Autowired
    private SecurityAuditService audit;
    @Autowired
    private SensitiveDataSanitizer sanitizer;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("merchant_idempotency", "payment_order", "channel_attempt", "payment_fact",
                "payment_event", "outbox_record", "event_log", "mock_channel_scenario", "mock_channel_tx",
                "mock_channel_refund_tx", "refund_order", "refund_idempotency", "refund_pool",
                "refund_reservation", "reservation_event", "refund_event", "payment_posting",
                "ledger_entry", "ledger_journal", "ledger_business_operation", "correction_link",
                "balance_projection", "account_balance", "ledger_account",
                "recovery_lease", "recovery_guard", "authority_scope", "late_effect", "approval_request",
                "security_audit", "beneficiary_account")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** R01：租约过期后接替者接管，旧工作者不得继续执行；恢复复用原渠道身份。 */
    @Test
    void r01_leaseTakeoverReusesOriginalAttemptIdentity() {
        mockChannel.setScenario("R01-ORDER", ChannelScenario.CHARGE_THEN_TIMEOUT);
        PaymentView created = paymentService.createPayment(MERCHANT, "K-R01", "R01-ORDER", 1000L, "CNY");
        sender.sendPending();

        String attemptId = jdbc.queryForObject(
                "SELECT attempt_id FROM channel_attempt WHERE payment_id = ?", String.class,
                created.paymentId());
        String providerKey = jdbc.queryForObject(
                "SELECT provider_idempotency_key FROM channel_attempt WHERE attempt_id = ?", String.class,
                attemptId);
        assertEquals("UNKNOWN", paymentService.getPayment(created.paymentId()).attemptStatus());

        RecoveryLeaseService.Lease first = leases.acquire(RECOVERY_TASK, "worker-a", 60_000L);
        assertNotNull(first);
        jdbc.update("UPDATE recovery_lease SET expires_at = ? WHERE task_name = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), RECOVERY_TASK);

        RecoveryLeaseService.Lease second = leases.acquire(RECOVERY_TASK, "worker-b", 60_000L);
        assertNotNull(second, "租约过期后接替者应能领取");
        assertNotEquals(first.token(), second.token());

        ApiConflictException lost = assertThrows(ApiConflictException.class,
                () -> leases.assertHeld(RECOVERY_TASK, first.token()));
        assertEquals("lease_lost", lost.code(), "旧工作者的迟到执行必须被拒绝");

        assertEquals(0, recovery.recoverOpenAttempts(), "租约被其他工作者持有时不得重复执行");
        assertEquals("UNKNOWN", paymentService.getPayment(created.paymentId()).attemptStatus());

        leases.release(RECOVERY_TASK, second.token());
        assertEquals(1, recovery.recoverOpenAttempts());

        PaymentView recovered = paymentService.getPayment(created.paymentId());
        assertEquals("SUCCEEDED", recovered.status());
        assertEquals("SUCCEEDED", recovered.attemptStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM channel_attempt"));
        assertEquals(1, count("SELECT COUNT(*) FROM mock_channel_tx"));
        assertEquals(1, count("SELECT COUNT(*) FROM payment_fact"));
        assertEquals(providerKey, jdbc.queryForObject(
                "SELECT provider_idempotency_key FROM channel_attempt WHERE attempt_id = ?", String.class,
                attemptId), "恢复必须复用原渠道幂等键，不能新建外部身份");
    }

    /** R02：去重证据缺失时暂停受影响重放，恢复后继续发送。 */
    @Test
    void r02_guardPausesRecoveryReplayUntilResumed() {
        PaymentView created = paymentService.createPayment(MERCHANT, "K-R02", "R02-ORDER", 1000L, "CNY");

        guard.pause(RecoveryGuardService.PAYMENT_RECOVERY, "灾备演练：去重历史待补齐", "drill-operator");
        TickService.TickResult paused = tickService.tick();
        assertEquals(0, paused.sent(), "门禁暂停时不得越过渠道发送边界");
        assertEquals(0, paused.recovered());
        assertEquals("CREATED", paymentService.getPayment(created.paymentId()).attemptStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM mock_channel_tx"));

        guard.resume(RecoveryGuardService.PAYMENT_RECOVERY, "drill-operator");
        assertEquals(1, sender.sendPending());
        assertEquals("SUCCEEDED", paymentService.getPayment(created.paymentId()).status());
        assertEquals(1, count("SELECT COUNT(*) FROM mock_channel_tx"));
    }

    /** R03：切换后旧地域写入者被拒绝，迟到外部效果进入核查而非直接应用。 */
    @Test
    void r03_staleWriterRejectedAndLateEffectInspected() {
        AuthorityService.ScopeState first = authority.switchTo("SCOPE-R03", "cn");
        assertEquals(1, first.epoch());

        AuthorityService.ScopeState switched = authority.switchTo("SCOPE-R03", "cn-east");
        assertEquals(2, switched.epoch());

        ApiConflictException stale = assertThrows(ApiConflictException.class,
                () -> authority.requireActive("SCOPE-R03", "cn", first.epoch()));
        assertEquals(AuthorityService.STALE_WRITER, stale.code());
        assertEquals(switched, authority.requireActive("SCOPE-R03", "cn-east", switched.epoch()));

        AuthorityService.LateEffectView late = authority.recordLateEffect("SCOPE-R03", "cn", first.epoch(),
                "ext-001", "card=4111111111111111 token=abc-secret", "old-region-writer");
        assertEquals(AuthorityService.STATUS_INSPECTION, late.status());
        assertFalse(late.detail().contains("4111111111111111"), "迟到效果明细不得保留完整卡号");
        assertFalse(late.detail().contains("abc-secret"), "迟到效果明细不得保留原始令牌");

        AuthorityService.LateEffectView replay = authority.recordLateEffect("SCOPE-R03", "cn", first.epoch(),
                "ext-001", "重复到达的同一外部效果", "old-region-writer");
        assertEquals(late.lateEffectId(), replay.lateEffectId(), "同一外部效果只登记一次");
        assertEquals(1, count("SELECT COUNT(*) FROM late_effect"));
    }

    /** R04：缺少审批的受益人变更被拒绝并留痕；通过审批后才允许修改。 */
    @Test
    void r04_beneficiaryChangeRequiresApprovalAndAuditsFailures() {
        String account = "6222020202020202";
        assertThrows(ApiForbiddenException.class,
                () -> beneficiaries.change(MERCHANT, account, "ops-a", null));
        assertEquals(0, count("SELECT COUNT(*) FROM beneficiary_account"), "缺少审批不得落地账户变更");

        List<SecurityAuditService.AuditView> rejected = audit.findByAction(BeneficiaryService.OPERATION);
        assertEquals(1, rejected.size());
        assertEquals(SecurityAuditService.OUTCOME_REJECTED, rejected.get(0).outcome());
        assertTrue(rejected.get(0).detail().contains("敏感操作缺少审批单"));
        assertFalse(rejected.get(0).detail().contains(account), "授权失败明细不得记录完整收款账号");

        ApprovalService.ApprovalView requested = approvals.request(BeneficiaryService.OPERATION, MERCHANT,
                "ops-a", "更换结算收款账户");
        assertEquals("PENDING", requested.status());
        ApprovalService.ApprovalView approved = approvals.approve(requested.approvalId(), "risk-b", "复核通过");
        assertEquals("APPROVED", approved.status());

        BeneficiaryService.BeneficiaryView changed = beneficiaries.change(MERCHANT, account, "ops-b",
                approved.approvalId());
        assertEquals(account, changed.bankAccountRef());
        assertEquals(1, count("SELECT COUNT(*) FROM beneficiary_account"));
        assertEquals(1, count("SELECT COUNT(*) FROM security_audit WHERE action = 'CHANGE_BENEFICIARY' "
                + "AND outcome = 'ACCEPTED'"));
    }

    /** R05：敏感数据进入审计明细前必须脱敏，卡号、密钥与令牌不得原样落库。 */
    @Test
    void r05_sensitiveDataIsMaskedBeforeAudit() {
        String raw = "card=4111111111111111 Authorization: Bearer abc.def.ghi secret=s3cr3t-xyz";
        String safe = sanitizer.sanitize(raw);
        assertFalse(safe.contains("4111111111111111"));
        assertFalse(safe.contains("abc.def.ghi"));
        assertFalse(safe.contains("s3cr3t-xyz"));
        assertTrue(safe.contains("<PAN_MASKED>"));
        assertTrue(safe.contains("<REDACTED>"));

        String auditId = audit.record("ops-a", "R05_TEST", MERCHANT,
                SecurityAuditService.OUTCOME_RECORDED, raw);
        String detail = jdbc.queryForObject("SELECT detail FROM security_audit WHERE audit_id = ?",
                String.class, auditId);
        assertEquals(safe, detail, "审计明细必须与脱敏结果一致");
        assertEquals(0, count("SELECT COUNT(*) FROM security_audit WHERE detail LIKE '%4111111111111111%'"));
        assertEquals(0, count("SELECT COUNT(*) FROM security_audit WHERE detail LIKE '%s3cr3t%'"));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
