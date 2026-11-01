package com.example.paymentcore;

import com.example.paymentcore.channel.MockChannel;
import com.example.paymentcore.domain.ChannelScenario;
import com.example.paymentcore.ledger.LedgerAvailability;
import com.example.paymentcore.monitoring.MonitoringService;
import com.example.paymentcore.payment.AttemptSender;
import com.example.paymentcore.payment.PaymentService;
import com.example.paymentcore.payment.PaymentView;
import com.example.paymentcore.refund.RefundService;
import com.example.paymentcore.refund.RefundView;
import com.example.paymentcore.security.ApprovalService;
import com.example.paymentcore.security.BeneficiaryService;
import com.example.paymentcore.security.OperationAuditService;
import com.example.paymentcore.security.RecoveryGuardService;
import com.example.paymentcore.security.SecurityAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 5 运行审计与监控断言：A01—A04、M01—M03。
 * 仅验证本地审计链与只读快照；不声明容量、告警阈值或生产 SLA。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditMonitoringStage5Tests {

    private static final String MERCHANT = "M-STAGE5";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private AttemptSender sender;
    @Autowired
    private RefundService refundService;
    @Autowired
    private MockChannel mockChannel;
    @Autowired
    private OperationAuditService operationAudit;
    @Autowired
    private ApprovalService approvals;
    @Autowired
    private BeneficiaryService beneficiaries;
    @Autowired
    private RecoveryGuardService guard;
    @Autowired
    private MonitoringService monitoring;
    @Autowired
    private LedgerAvailability ledgerAvailability;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("recon_difference", "recon_record", "recon_page", "recon_report",
                "bank_allocation", "bank_statement", "payout_event", "payout_order", "settlement_item",
                "clearing_entry", "settlement_batch", "mock_bank_scenario", "mock_bank_tx",
                "merchant_idempotency", "payment_order", "channel_attempt", "payment_fact", "payment_event",
                "outbox_record", "event_log", "mock_channel_scenario", "mock_channel_tx", "mock_channel_refund_tx",
                "refund_order", "refund_idempotency", "refund_pool", "refund_reservation", "reservation_event",
                "refund_event", "payment_posting", "ledger_entry", "ledger_journal", "ledger_business_operation",
                "correction_link", "balance_projection", "account_balance", "ledger_account",
                "recovery_lease", "recovery_guard", "authority_scope", "late_effect", "approval_request",
                "security_audit", "beneficiary_account", "operation_audit")) {
            jdbc.update("DELETE FROM " + table);
        }
        ledgerAvailability.setAvailable(true);
    }

    /** A01：支付受理写入操作审计，回答谁、哪类资源、影响哪个投影。 */
    @Test
    void a01_paymentCreationIsAuditedWithActorAndProjection() {
        PaymentView payment = paymentService.createPayment(MERCHANT, "K-A01", "O-A01", 1000L, "CNY");

        List<OperationAuditService.AuditView> rows = operationAudit.findByBusinessId(payment.paymentId());
        assertEquals(1, rows.size());
        OperationAuditService.AuditView row = rows.get(0);
        assertEquals("PAYMENT_CREATE", row.action());
        assertEquals(MERCHANT, row.actor());
        assertEquals("PAYMENT", row.resourceType());
        assertEquals("payment_order", row.projection());
        assertEquals(SecurityAuditService.OUTCOME_ACCEPTED, row.outcome());
        assertNotNull(row.traceId());
        assertFalse(row.traceId().isBlank());
        assertNull(row.approvalId());
    }

    /** A02：审计明细脱敏；trace_id 只关联操作，不成为业务唯一键。 */
    @Test
    void a02_auditDetailIsMaskedAndTraceGroupsOperations() {
        String traceId = "trace-a02";
        operationAudit.record(new OperationAuditService.Entry("ops-a", "AUDIT_TEST", "TEST", "B-A02",
                null, null, "test_projection", null, SecurityAuditService.OUTCOME_ACCEPTED,
                "card=4111111111111111 token=abc-secret", traceId));
        operationAudit.record(new OperationAuditService.Entry("ops-b", "AUDIT_TEST", "TEST", "B-A02",
                null, null, "test_projection", null, SecurityAuditService.OUTCOME_ACCEPTED,
                "second operation", traceId));

        List<OperationAuditService.AuditView> rows = operationAudit.findByTraceId(traceId);
        assertEquals(2, rows.size(), "同一 trace 可关联多次操作，审计不承担业务唯一约束");
        for (OperationAuditService.AuditView row : rows) {
            assertEquals("B-A02", row.businessId());
            assertFalse(row.detail().contains("4111111111111111"));
            assertFalse(row.detail().contains("abc-secret"));
        }
        assertTrue(rows.get(0).detail().contains("<PAN_MASKED>"));
        assertTrue(rows.get(0).detail().contains("<REDACTED>"));
    }

    /** A03：退款受理审计关联原支付与退款投影。 */
    @Test
    void a03_refundCreationAuditLinksOriginalPayment() {
        PaymentView payment = paymentService.createPayment(MERCHANT, "K-A03", "O-A03", 1000L, "CNY");
        assertEquals(1, sender.sendPending());
        assertEquals("SUCCEEDED", paymentService.getPayment(payment.paymentId()).status());

        RefundView refund = refundService.createRefund(MERCHANT, "RK-A03", payment.paymentId(), 300L);
        List<OperationAuditService.AuditView> rows = operationAudit.findByBusinessId(refund.refundId());
        assertEquals(1, rows.size());
        OperationAuditService.AuditView row = rows.get(0);
        assertEquals("REFUND_CREATE", row.action());
        assertEquals(MERCHANT, row.actor());
        assertEquals("REFUND", row.resourceType());
        assertEquals(payment.paymentId(), row.evidenceRef());
        assertEquals("refund_order", row.projection());
    }

    /** A04：受益人变更审计必须带审批单号与落地投影。 */
    @Test
    void a04_beneficiaryChangeAuditCarriesApproval() {
        ApprovalService.ApprovalView requested = approvals.request(BeneficiaryService.OPERATION, MERCHANT,
                "ops-a", "更换收款账户");
        ApprovalService.ApprovalView approved = approvals.approve(requested.approvalId(), "risk-b", "复核通过");
        beneficiaries.change(MERCHANT, "6222020202020202", "ops-b", approved.approvalId());

        List<OperationAuditService.AuditView> rows = operationAudit.findByBusinessId(MERCHANT);
        assertEquals(1, rows.size());
        OperationAuditService.AuditView row = rows.get(0);
        assertEquals(BeneficiaryService.OPERATION, row.action());
        assertEquals("ops-b", row.actor());
        assertEquals("BENEFICIARY", row.resourceType());
        assertEquals("beneficiary_account", row.projection());
        assertEquals(approved.approvalId(), row.approvalId());
    }

    /** M01：未发送支付在快照中可见，健康状态不因正常待发送积压而劣化。 */
    @Test
    void m01_snapshotShowsNewPaymentWithoutDegradingHealth() {
        paymentService.createPayment(MERCHANT, "K-M01", "O-M01", 1000L, "CNY");

        MonitoringService.Snapshot snapshot = monitoring.snapshot();
        assertEquals(1, snapshot.acceptedPayments());
        assertEquals(1, snapshot.attempts().created());
        assertEquals(0, snapshot.attempts().unknown());
        assertEquals(1, snapshot.pendingOutbox());
        assertEquals(0, snapshot.pendingPosting());
        assertEquals(0, snapshot.openPayouts());
        assertEquals(0, snapshot.openReconDifferences());
        assertTrue(snapshot.ledgerAvailable());

        MonitoringService.HealthView health = monitoring.health();
        assertEquals(MonitoringService.STATUS_UP, health.status());
        assertTrue(health.signals().isEmpty());
    }

    /** M02：UNKNOWN 暴露账龄并触发关注；恢复门禁暂停后健康状态降级。 */
    @Test
    void m02_unknownRaisesAttentionAndPausedRecoveryDegradesHealth() {
        mockChannel.setScenario("O-M02", ChannelScenario.CHARGE_THEN_TIMEOUT);
        paymentService.createPayment(MERCHANT, "K-M02", "O-M02", 1000L, "CNY");
        assertEquals(1, sender.sendPending());

        MonitoringService.Snapshot snapshot = monitoring.snapshot();
        assertEquals(1, snapshot.attempts().unknown());
        assertNotNull(snapshot.attempts().oldestUnknownAgeSeconds());
        assertTrue(snapshot.attempts().oldestUnknownAgeSeconds() >= 0);

        MonitoringService.HealthView attention = monitoring.health();
        assertEquals(MonitoringService.STATUS_ATTENTION, attention.status());
        assertTrue(attention.signals().contains("unknown-attempts"));

        guard.pause(RecoveryGuardService.PAYMENT_RECOVERY, "灾备演练", "drill-operator");
        MonitoringService.HealthView degraded = monitoring.health();
        assertEquals(MonitoringService.STATUS_DEGRADED, degraded.status());
        assertTrue(degraded.signals().contains("recovery-paused"));
        assertTrue(monitoring.snapshot().recoveryPaused());
    }

    /** M03：账本不可用是本地降级信号，不会被误报为支付失败。 */
    @Test
    void m03_ledgerUnavailableDegradesHealth() {
        ledgerAvailability.setAvailable(false);

        MonitoringService.Snapshot snapshot = monitoring.snapshot();
        assertFalse(snapshot.ledgerAvailable());
        MonitoringService.HealthView health = monitoring.health();
        assertEquals(MonitoringService.STATUS_DEGRADED, health.status());
        assertTrue(health.signals().contains("ledger-unavailable"));
    }
}
