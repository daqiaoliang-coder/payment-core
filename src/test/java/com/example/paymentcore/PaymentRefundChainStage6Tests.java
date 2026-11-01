package com.example.paymentcore;

import com.example.paymentcore.channel.MockChannel;
import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.domain.ChannelScenario;
import com.example.paymentcore.ledger.LedgerAccountService;
import com.example.paymentcore.ledger.LedgerConstants;
import com.example.paymentcore.ledger.LedgerQuery;
import com.example.paymentcore.payment.AttemptSender;
import com.example.paymentcore.payment.PaymentPostingService;
import com.example.paymentcore.payment.PaymentService;
import com.example.paymentcore.payment.PaymentView;
import com.example.paymentcore.payment.TickService;
import com.example.paymentcore.refund.RefundRecoveryService;
import com.example.paymentcore.refund.RefundSender;
import com.example.paymentcore.refund.RefundService;
import com.example.paymentcore.refund.RefundView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 6 支付与退款链路断言：C01—C06。
 * 覆盖“支付成功—记账—退款成功/未知/失败”的跨层组合，不重复单层边界用例。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentRefundChainStage6Tests {

    private static final String MERCHANT = "M-STAGE6";
    private static final String MERCHANT_OTHER = "M-STAGE6-OTHER";
    private static final String BOOK = LedgerConstants.BOOK_ID;

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private AttemptSender attemptSender;
    @Autowired
    private PaymentPostingService posting;
    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundSender refundSender;
    @Autowired
    private RefundRecoveryService refundRecovery;
    @Autowired
    private TickService tickService;
    @Autowired
    private MockChannel mockChannel;
    @Autowired
    private LedgerAccountService accounts;
    @Autowired
    private LedgerQuery ledgerQuery;
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
    }

    /** C01：支付成功、记账、全额退款成功，三层状态与会计分录同时收敛。 */
    @Test
    void c01_paymentPostingThenFullRefundConvergesAcrossLayers() {
        PaymentView payment = paidPayment(MERCHANT, "O-C01", 1000);
        assertEquals("POSTED", postingStatus(payment.paymentId()));
        assertEquals(1000, balance(receivable()), "收款应收渠道");
        assertEquals(990, balance(payable()), "收款应付商户净额，扣除 1% 手续费");
        assertEquals(10, balance(feeIncome()), "手续费收入");

        RefundView refund = refund("RK-C01", payment.paymentId(), 1000);
        assertEquals(1, refundSender.sendPending());
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());
        assertEquals("CONFIRMED", reservationStatus(refund.refundId()));
        assertEquals(1000, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(0, poolOf(payment.paymentId()).reservedTotal());

        assertEquals(0, balance(receivable()));
        // 手续费不随退款返还：应付商户余额为 -10，仍与应收、手续费收入保持会计恒等
        assertEquals(-10, balance(payable()));
        assertEquals(10, balance(feeIncome()));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_business_operation "
                + "WHERE operation_type = 'REFUND_POSTING' AND business_id = ?", refund.refundId()));
    }

    /** C02：退款超时进入 UNKNOWN 并占额，查单恢复后仅生成一份资金事实。 */
    @Test
    void c02_refundTimeoutRecoversWithSingleEvidenceAndReservation() {
        PaymentView payment = paidPayment(MERCHANT, "O-C02", 1000);
        mockChannel.setScenario("O-C02", ChannelScenario.CHARGE_THEN_TIMEOUT);

        RefundView refund = refund("RK-C02", payment.paymentId(), 300);
        assertEquals(1, refundSender.sendPending());
        assertEquals("UNKNOWN", refundService.getRefund(refund.refundId()).status());
        assertEquals("RESERVED", reservationStatus(refund.refundId()));
        assertEquals(0, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(300, poolOf(payment.paymentId()).reservedTotal());
        assertEquals(1000, balance(receivable()), "UNKNOWN 不得提前释放应收");
        assertEquals(990, balance(payable()), "UNKNOWN 不得提前冲减应付");

        assertEquals(1, refundRecovery.recoverOpenRefunds());
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());
        assertEquals("CONFIRMED", reservationStatus(refund.refundId()));
        assertEquals(300, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(0, poolOf(payment.paymentId()).reservedTotal());
        assertEquals(1, count("SELECT COUNT(*) FROM mock_channel_refund_tx"));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_business_operation "
                + "WHERE operation_type = 'REFUND_POSTING' AND business_id = ?", refund.refundId()));
        assertEquals(700, balance(receivable()));
        assertEquals(690, balance(payable()));
    }

    /** C03：渠道明确拒绝释放额度；同链路换幂等键可重试成功。 */
    @Test
    void c03_refundDeclineReleasesQuotaAndRetrySucceeds() {
        PaymentView payment = paidPayment(MERCHANT, "O-C03", 1000);
        mockChannel.setScenario("O-C03", ChannelScenario.DECLINE);

        RefundView declined = refund("RK-C03-A", payment.paymentId(), 400);
        assertEquals(1, refundSender.sendPending());
        assertEquals("FAILED", refundService.getRefund(declined.refundId()).status());
        assertEquals("RELEASED", reservationStatus(declined.refundId()));
        assertEquals(0, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(0, poolOf(payment.paymentId()).reservedTotal(), "明确失败必须归还额度");
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_business_operation "
                + "WHERE operation_type = 'REFUND_POSTING' AND business_id = ?", declined.refundId()));

        mockChannel.setScenario("O-C03", ChannelScenario.SUCCESS);
        RefundView retried = refund("RK-C03-B", payment.paymentId(), 400);
        assertEquals(1, refundSender.sendPending());
        assertEquals("SUCCEEDED", refundService.getRefund(retried.refundId()).status());
        assertEquals(400, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(600, balance(receivable()));
        assertEquals(590, balance(payable()));
    }

    /** C04：多笔部分退款各自成功并累计；超出可退上限时不产生第三笔退款单。 */
    @Test
    void c04_partialRefundsAccumulateUntilQuotaExhausted() {
        PaymentView payment = paidPayment(MERCHANT, "O-C04", 1000);
        RefundView first = refund("RK-C04-A", payment.paymentId(), 400);
        RefundView second = refund("RK-C04-B", payment.paymentId(), 600);

        assertEquals(2, refundSender.sendPending());
        assertEquals("SUCCEEDED", refundService.getRefund(first.refundId()).status());
        assertEquals("SUCCEEDED", refundService.getRefund(second.refundId()).status());
        assertEquals(1000, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(0, poolOf(payment.paymentId()).reservedTotal());
        assertEquals(2, count("SELECT COUNT(*) FROM ledger_business_operation "
                + "WHERE operation_type = 'REFUND_POSTING' AND business_id IN (?, ?)",
                first.refundId(), second.refundId()));
        assertEquals(0, balance(receivable()));
        assertEquals(-10, balance(payable()));

        ApiConflictException exhausted = assertThrows(ApiConflictException.class,
                () -> refund("RK-C04-C", payment.paymentId(), 1));
        assertEquals("refund_quota_exceeded", exhausted.code());
        assertEquals(2, count("SELECT COUNT(*) FROM refund_order"), "被拒绝的退款不得落单");
    }

    /** C05：支付未知经恢复确认后再退款，支付事实与凭证均只出现一次。 */
    @Test
    void c05_recoveredPaymentStillPostsOnceAndRefunds() {
        mockChannel.setScenario("O-C05", ChannelScenario.CHARGE_THEN_TIMEOUT);
        PaymentView created = paymentService.createPayment(MERCHANT, "K-O-C05", "O-C05", 1000, "CNY");

        TickService.TickResult firstTick = tickService.tick();
        assertEquals(1, firstTick.sent());
        assertEquals(1, firstTick.recovered());
        assertEquals(1, firstTick.posted());

        PaymentView recovered = paymentService.getPayment(created.paymentId());
        assertEquals("SUCCEEDED", recovered.status());
        assertEquals("POSTED", postingStatus(created.paymentId()));
        assertEquals(1, count("SELECT COUNT(*) FROM payment_fact f JOIN channel_attempt a "
                + "ON a.operation_id = f.operation_id WHERE a.payment_id = ?", created.paymentId()));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_business_operation "
                + "WHERE operation_type = 'PAYMENT_POSTING' AND business_id = ?", created.paymentId()));

        // 支付查单已恢复成功，退款场景改回正常成功，继续验证链路组合
        mockChannel.setScenario("O-C05", ChannelScenario.SUCCESS);
        RefundView refund = refund("RK-C05", created.paymentId(), 200);
        assertEquals(1, refundSender.sendPending());
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());
        assertEquals(200, poolOf(created.paymentId()).refundedTotal());
        assertEquals(800, balance(receivable()));
        assertEquals(790, balance(payable()));
    }

    /** C06：退款幂等重放不复制资金动作；失败支付、跨商户与参数冲突均被拒绝。 */
    @Test
    void c06_refundReplayAndBoundaryGuards() {
        PaymentView payment = paidPayment(MERCHANT, "O-C06", 1000);
        RefundView refund = refund("RK-C06", payment.paymentId(), 1000);
        assertEquals(1, refundSender.sendPending());
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());

        RefundView replay = refund("RK-C06", payment.paymentId(), 1000);
        assertEquals(refund.refundId(), replay.refundId(), "同一幂等键必须返回原退款单");
        assertEquals(1, count("SELECT COUNT(*) FROM refund_order"));
        assertEquals(1, count("SELECT COUNT(*) FROM refund_reservation"));

        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> refund("RK-C06", payment.paymentId(), 200));
        assertEquals("refund_idempotency_conflict", conflict.code());

        mockChannel.setScenario("O-C06-FAIL", ChannelScenario.DECLINE);
        PaymentView failed = paymentService.createPayment(MERCHANT, "K-O-C06-F", "O-C06-FAIL", 500, "CNY");
        assertEquals(1, attemptSender.sendPending());
        assertEquals("FAILED", paymentService.getPayment(failed.paymentId()).status());
        ApiConflictException notRefundable = assertThrows(ApiConflictException.class,
                () -> refund("RK-C06-F", failed.paymentId(), 100));
        assertEquals("refund_not_allowed", notRefundable.code());

        PaymentView otherMerchantPayment = paidPayment(MERCHANT_OTHER, "O-C06-OTHER", 500);
        ApiForbiddenException forbidden = assertThrows(ApiForbiddenException.class,
                () -> refundService.createRefund(MERCHANT, "RK-C06-X", otherMerchantPayment.paymentId(), 100));
        assertTrue(forbidden.getMessage().contains("无权"));
    }

    private PaymentView paidPayment(String merchantId, String orderId, long amountMinor) {
        PaymentView created = paymentService.createPayment(merchantId, "K-" + orderId, orderId, amountMinor,
                "CNY");
        assertEquals(1, attemptSender.sendPending());
        PaymentView succeeded = paymentService.getPayment(created.paymentId());
        assertEquals("SUCCEEDED", succeeded.status(), "测试前置：支付必须成功");
        assertEquals(1, posting.postPending());
        return succeeded;
    }

    private RefundView refund(String key, String paymentId, long amountMinor) {
        return refundService.createRefund(MERCHANT, key, paymentId, amountMinor);
    }

    private String postingStatus(String paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment_posting WHERE payment_id = ?", String.class,
                paymentId);
    }

    private String reservationStatus(String refundId) {
        return jdbc.queryForObject("SELECT status FROM refund_reservation WHERE refund_id = ?", String.class,
                refundId);
    }

    private String receivable() {
        return accounts.receivableChannel(BOOK, "CNY");
    }

    private String payable() {
        return accounts.merchantPayable(BOOK, MERCHANT, "CNY");
    }

    private String feeIncome() {
        return accounts.feeIncome(BOOK, "CNY");
    }

    private long balance(String accountId) {
        return ledgerQuery.balance(accountId).postedBalance();
    }

    private Pool poolOf(String paymentId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT refunded_total, reserved_total FROM refund_pool WHERE payment_id = ?", paymentId);
        java.util.Map<String, Object> row = rows.get(0);
        return new Pool(((Number) row.get("refunded_total")).longValue(),
                ((Number) row.get("reserved_total")).longValue());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private record Pool(long refundedTotal, long reservedTotal) {
    }
}
