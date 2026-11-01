package com.example.paymentcore;

import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.channel.MockChannel;
import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.common.InsufficientFundsException;
import com.example.paymentcore.domain.ChannelScenario;
import com.example.paymentcore.domain.LedgerSide;
import com.example.paymentcore.domain.OwnerScope;
import com.example.paymentcore.ledger.BalanceProjectionService;
import com.example.paymentcore.ledger.JournalEntryRequest;
import com.example.paymentcore.ledger.JournalRequest;
import com.example.paymentcore.ledger.JournalResult;
import com.example.paymentcore.ledger.LedgerAccountService;
import com.example.paymentcore.ledger.LedgerAvailability;
import com.example.paymentcore.ledger.LedgerConstants;
import com.example.paymentcore.ledger.LedgerQuery;
import com.example.paymentcore.ledger.LedgerService;
import com.example.paymentcore.payment.AttemptSender;
import com.example.paymentcore.payment.PaymentPostingService;
import com.example.paymentcore.payment.PaymentService;
import com.example.paymentcore.payment.PaymentView;
import com.example.paymentcore.refund.RefundRecoveryService;
import com.example.paymentcore.refund.RefundSender;
import com.example.paymentcore.refund.RefundService;
import com.example.paymentcore.refund.RefundTransactions;
import com.example.paymentcore.refund.RefundView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 2 适用断言：L01—L08、L10 与 M08—M10。
 * L09（跨分片转移）在单库骨架中不适用；真实渠道能力仍需单独验证。
 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerStage2Tests {

    private static final String MERCHANT = "M-STAGE2";
    private static final String BOOK = LedgerConstants.BOOK_ID;

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private AttemptSender attemptSender;
    @Autowired
    private PaymentPostingService paymentPostingService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private RefundSender refundSender;
    @Autowired
    private RefundRecoveryService refundRecovery;
    @Autowired
    private RefundTransactions refundTransactions;
    @Autowired
    private MockChannel mockChannel;
    @Autowired
    private LedgerService ledger;
    @Autowired
    private LedgerAccountService accounts;
    @Autowired
    private LedgerQuery ledgerQuery;
    @Autowired
    private BalanceProjectionService projection;
    @Autowired
    private LedgerAvailability ledgerAvailability;
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
        ledgerAvailability.setAvailable(true);
    }

    /** L01：同一凭证包含多币种分录时逐币种平衡，混币不能靠数值相等配平。 */
    @Test
    void l01_multiCurrencyJournalBalancesPerCurrency() {
        String cnyReceivable = accounts.receivableChannel(BOOK, "CNY");
        String cnyPayable = accounts.merchantPayable(BOOK, MERCHANT, "CNY");
        String usdReceivable = accounts.receivableChannel(BOOK, "USD");
        String usdPayable = accounts.merchantPayable(BOOK, MERCHANT, "USD");

        JournalResult accepted = ledger.postJournal(JournalRequest.of(BOOK, MERCHANT, "TEST", "L01-OK", "TEST",
                List.of(new JournalEntryRequest(cnyReceivable, "DEBIT", 1000),
                        new JournalEntryRequest(cnyPayable, "CREDIT", 1000),
                        new JournalEntryRequest(usdReceivable, "DEBIT", 500),
                        new JournalEntryRequest(usdPayable, "CREDIT", 500))));
        assertEquals(4, ledgerQuery.entryCount(accepted.journalId()));

        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> ledger.postJournal(JournalRequest.of(BOOK, MERCHANT, "TEST", "L01-MIXED", "TEST",
                        List.of(new JournalEntryRequest(cnyReceivable, "DEBIT", 100),
                                new JournalEntryRequest(usdPayable, "CREDIT", 100)))));
        assertEquals("journal_unbalanced", conflict.code());
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_journal"));
    }

    /** L02：余额 100 时两笔并发各申请 80，至多一笔成功且另一笔无资金效果。 */
    @Test
    void l02_concurrentDebitsOnlyOneSucceeds() throws Exception {
        String reserve = accounts.ensureAccount(BOOK, OwnerScope.MERCHANT, MERCHANT, "CNY", "TEST_RESERVE",
                LedgerSide.CREDIT, false).accountId();
        String bank = accounts.bank(BOOK, "CNY");
        ledger.postJournal(JournalRequest.of(BOOK, MERCHANT, "TEST_SEED", "L02-SEED", "TEST",
                List.of(new JournalEntryRequest(bank, "DEBIT", 100),
                        new JournalEntryRequest(reserve, "CREDIT", 100))));
        assertEquals(100, ledgerQuery.balance(reserve).postedBalance());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (String businessId : List.of("L02-A", "L02-B")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        ledger.postJournal(JournalRequest.of(BOOK, MERCHANT, "TEST_DEBIT", businessId, "TEST",
                                List.of(new JournalEntryRequest(reserve, "DEBIT", 80),
                                        new JournalEntryRequest(bank, "CREDIT", 80))));
                        return true;
                    } catch (InsufficientFundsException e) {
                        return false;
                    }
                }));
            }
            start.countDown();

            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertEquals(1, successes, "至多一笔可以动用该余额");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(20, ledgerQuery.balance(reserve).postedBalance());
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_business_operation WHERE operation_type = 'TEST_DEBIT'"));
    }

    /** L03：成功退 70、预留 20 后，再申请 20 必须被拒绝；退款使用独立凭证。 */
    @Test
    void l03_refundedAndReservedCannotExceedRefundableBase() {
        PaymentView payment = succeededPayment(MERCHANT, "ORDER-L03", 100);

        RefundView first = refundService.createRefund(MERCHANT, "RF-L03-1", payment.paymentId(), 70);
        refundSender.sendPending();
        assertEquals("SUCCEEDED", refundService.getRefund(first.refundId()).status());

        RefundView reserved = refundService.createRefund(MERCHANT, "RF-L03-2", payment.paymentId(), 20);
        assertEquals("ACCEPTED", reserved.status());

        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> refundService.createRefund(MERCHANT, "RF-L03-3", payment.paymentId(), 20));
        assertEquals("refund_quota_exceeded", conflict.code());
        assertEquals(2, count("SELECT COUNT(*) FROM refund_order"));
        assertEquals(2, count("SELECT COUNT(*) FROM refund_idempotency"));

        // 退款凭证：借记商户应付、贷记应收渠道
        String refundJournal = jdbc.queryForObject(
                "SELECT journal_id FROM ledger_business_operation WHERE operation_type = 'REFUND_POSTING' "
                        + "AND business_id = ?", String.class, first.refundId());
        assertEquals(2, ledgerQuery.entryCount(refundJournal));
        // 应付商户初始为收款净额 99，退款凭证借记 70 后余额 29
        assertEquals(29, ledgerQuery.balance(accounts.merchantPayable(BOOK, MERCHANT, "CNY")).postedBalance());
    }

    /** L04：同一退款的确认与释放并发时预留只终结一次。 */
    @Test
    void l04_concurrentConfirmAndReleaseTerminatesReservationOnce() throws Exception {
        PaymentView payment = succeededPayment(MERCHANT, "ORDER-L04", 100);
        RefundView refund = refundService.createRefund(MERCHANT, "RF-L04", payment.paymentId(), 50);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return refundTransactions.confirmSuccess(refund.refundId(),
                            ChannelResult.success("rf-ok", 50L, "CNY"));
                } catch (EvidenceConflictException e) {
                    return false;
                }
            }));
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return refundTransactions.release(refund.refundId(), "渠道明确失败");
                } catch (EvidenceConflictException e) {
                    return false;
                }
            }));
            start.countDown();

            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners, "确认与释放只能有一个生效");
        } finally {
            pool.shutdownNow();
        }

        String reservationStatus = jdbc.queryForObject(
                "SELECT status FROM refund_reservation WHERE refund_id = ?", String.class, refund.refundId());
        String refundStatus = jdbc.queryForObject(
                "SELECT status FROM refund_order WHERE refund_id = ?", String.class, refund.refundId());
        RefundRepositoryPool poolRow = poolOf(payment.paymentId());
        assertEquals(0, poolRow.reservedTotal());
        if ("CONFIRMED".equals(reservationStatus)) {
            assertEquals("SUCCEEDED", refundStatus);
            assertEquals(50, poolRow.refundedTotal());
        } else {
            assertEquals("RELEASED", reservationStatus);
            assertEquals("FAILED", refundStatus);
            assertEquals(0, poolRow.refundedTotal());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM reservation_event WHERE event_type IN ('CONFIRMED','RELEASED')"));
    }

    /** L05：退款超时进入 UNKNOWN 并保留占额，恢复成功前不能再次超退。 */
    @Test
    void l05_unknownRefundKeepsReservationUntilRecovery() {
        PaymentView payment = succeededPayment(MERCHANT, "ORDER-L05", 100);
        mockChannel.setScenario("ORDER-L05", ChannelScenario.CHARGE_THEN_TIMEOUT);

        RefundView refund = refundService.createRefund(MERCHANT, "RF-L05", payment.paymentId(), 60);
        refundSender.sendPending();
        assertEquals("UNKNOWN", refundService.getRefund(refund.refundId()).status());
        assertEquals("RESERVED", jdbc.queryForObject(
                "SELECT status FROM refund_reservation WHERE refund_id = ?", String.class, refund.refundId()));

        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> refundService.createRefund(MERCHANT, "RF-L05-X", payment.paymentId(), 50));
        assertEquals("refund_quota_exceeded", conflict.code());

        refundRecovery.recoverOpenRefunds();
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM refund_reservation WHERE refund_id = ?", String.class, refund.refundId()));
        assertEquals(60, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(0, poolOf(payment.paymentId()).reservedTotal());

        RefundView remaining = refundService.createRefund(MERCHANT, "RF-L05-2", payment.paymentId(), 40);
        assertEquals("ACCEPTED", remaining.status());
    }

    /** L06：退款成功后的相反证据只追加冲突记录，不删除原成功历史。 */
    @Test
    void l06_lateFailureOnlyAppendsEvidence() {
        PaymentView payment = succeededPayment(MERCHANT, "ORDER-L06", 100);
        RefundView refund = refundService.createRefund(MERCHANT, "RF-L06", payment.paymentId(), 50);
        refundSender.sendPending();
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());

        assertThrows(EvidenceConflictException.class,
                () -> refundTransactions.release(refund.refundId(), "渠道退回"));

        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT status FROM refund_reservation WHERE refund_id = ?", String.class, refund.refundId()));
        assertEquals(50, poolOf(payment.paymentId()).refundedTotal());
        assertEquals(1, count("SELECT COUNT(*) FROM refund_event WHERE status = 'EVIDENCE_CONFLICT'"));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_business_operation "
                + "WHERE operation_type = 'REFUND_POSTING' AND business_id = ?", refund.refundId()));

        // 重复成功证据不产生第二份凭证
        assertFalse(refundTransactions.confirmSuccess(refund.refundId(),
                ChannelResult.success("rf-again", 50L, "CNY")));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_business_operation "
                + "WHERE operation_type = 'REFUND_POSTING' AND business_id = ?", refund.refundId()));
    }

    /** L07：商户甲使用商户乙的支付单或账户必须被拒绝，不产生跨商户资金变化。 */
    @Test
    void l07_crossMerchantAccessIsForbidden() {
        PaymentView payment = succeededPayment("M-OWNER", "ORDER-L07", 100);

        assertThrows(ApiForbiddenException.class,
                () -> refundService.createRefund(MERCHANT, "RF-L07", payment.paymentId(), 10));
        assertEquals(0, count("SELECT COUNT(*) FROM refund_order"));

        String bank = accounts.bank(BOOK, "CNY");
        String otherPayable = accounts.merchantPayable(BOOK, "M-OWNER", "CNY");
        assertThrows(ApiForbiddenException.class,
                () -> ledger.postJournal(JournalRequest.of(BOOK, MERCHANT, "TEST", "L07-ACCOUNT", "TEST",
                        List.of(new JournalEntryRequest(bank, "DEBIT", 10),
                                new JournalEntryRequest(otherPayable, "CREDIT", 10)))));
        assertEquals(0, ledgerQuery.journalCount(BOOK + "|" + MERCHANT + "|TEST|L07-ACCOUNT"));
    }

    /** L08：投影落后于源账本时禁止使用，重建后必须与源账本一致。 */
    @Test
    void l08_projectionLagIsDetectedAndRebuildMatchesSource() {
        PaymentView payment = succeededPayment(MERCHANT, "ORDER-L08", 100);
        String receivable = accounts.receivableChannel(BOOK, "CNY");
        String payable = accounts.merchantPayable(BOOK, MERCHANT, "CNY");

        projection.rebuild();
        assertEquals(ledgerQuery.balance(receivable).postedBalance(),
                projection.snapshot(receivable).postedBalance());
        assertEquals(ledgerQuery.sourceSequence(), projection.snapshot(receivable).sequence());
        projection.assertFresh(receivable);

        RefundView refund = refundService.createRefund(MERCHANT, "RF-L08", payment.paymentId(), 30);
        refundSender.sendPending();
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());
        ApiConflictException stale = assertThrows(ApiConflictException.class,
                () -> projection.assertFresh(receivable));
        assertEquals("stale_projection", stale.code());

        refundService.createRefund(MERCHANT, "RF-L08-R", payment.paymentId(), 20);
        projection.rebuild();
        assertEquals(70, projection.snapshot(receivable).postedBalance());
        assertEquals(ledgerQuery.balance(receivable).postedBalance(),
                projection.snapshot(receivable).postedBalance());
        assertEquals(20, projection.snapshot(payable).activeReserved());
        projection.assertFresh(receivable);
    }

    /** L10：同一修正动作回调重复时只执行一次，原凭证和关联保留。 */
    @Test
    void l10_duplicateCorrectionCallbackExecutesOnce() {
        String receivable = accounts.receivableChannel(BOOK, "CNY");
        String payable = accounts.merchantPayable(BOOK, MERCHANT, "CNY");
        JournalResult base = ledger.postJournal(JournalRequest.of(BOOK, MERCHANT, "TEST", "L10-BASE", "TEST",
                List.of(new JournalEntryRequest(receivable, "DEBIT", 100),
                        new JournalEntryRequest(payable, "CREDIT", 100))));

        JournalRequest correction = JournalRequest.of(BOOK, MERCHANT, "IGNORED", "IGNORED", "IGNORED",
                List.of(new JournalEntryRequest(payable, "DEBIT", 100),
                        new JournalEntryRequest(receivable, "CREDIT", 100)));

        JournalResult first = ledger.postCorrection(base.journalId(), "L10-CORR", "差错修正", "审批人-1",
                correction);
        JournalResult second = ledger.postCorrection(base.journalId(), "L10-CORR", "差错修正", "审批人-1",
                correction);

        assertEquals(first.journalId(), second.journalId());
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(1, count("SELECT COUNT(*) FROM correction_link"));
        assertEquals(2, count("SELECT COUNT(*) FROM ledger_journal"));
        assertEquals(2, ledgerQuery.entryCount(first.journalId()));
        assertEquals(base.journalId(), jdbc.queryForObject(
                "SELECT original_journal_id FROM correction_link WHERE correction_journal_id = ?",
                String.class, first.journalId()));

        assertThrows(IllegalArgumentException.class,
                () -> ledger.postCorrection(base.journalId(), "L10-CORR-2", null, "审批人-2", correction));
    }

    /** M08：重复记账命令返回既有凭证，不新增第二份原始凭证。 */
    @Test
    void m08_duplicatePostingCommandReturnsExistingJournal() {
        String receivable = accounts.receivableChannel(BOOK, "CNY");
        String payable = accounts.merchantPayable(BOOK, MERCHANT, "CNY");
        JournalRequest request = JournalRequest.of(BOOK, MERCHANT, "TEST", "M08", "TEST",
                List.of(new JournalEntryRequest(receivable, "DEBIT", 100),
                        new JournalEntryRequest(payable, "CREDIT", 100)));

        JournalResult first = ledger.postJournal(request);
        JournalResult second = ledger.postJournal(request);

        assertEquals(first.journalId(), second.journalId());
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(1, ledgerQuery.journalCount(ledger.operationKey(request)));
        assertEquals(2, ledgerQuery.entryCount(first.journalId()));

        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> ledger.postJournal(JournalRequest.of(BOOK, MERCHANT, "TEST", "M08", "TEST",
                        List.of(new JournalEntryRequest(receivable, "DEBIT", 200),
                                new JournalEntryRequest(payable, "CREDIT", 200)))));
        assertEquals("ledger_operation_conflict", conflict.code());
    }

    /** M09：凭证已提交但本地记账确认丢失时，恢复记账状态而非再次记账。 */
    @Test
    void m09_lostPostingConfirmationRecoversWithoutDuplicateJournal() {
        PaymentView payment = succeededPayment(MERCHANT, "ORDER-M09", 100);
        String journalId = jdbc.queryForObject(
                "SELECT journal_id FROM payment_posting WHERE payment_id = ?", String.class,
                payment.paymentId());

        // 模拟“凭证已提交、本地确认丢失”
        jdbc.update("UPDATE payment_posting SET status = 'PENDING', journal_id = NULL WHERE payment_id = ?",
                payment.paymentId());
        jdbc.update("UPDATE payment_order SET posting_status = 'PENDING_POSTING' WHERE payment_id = ?",
                payment.paymentId());

        assertEquals(1, paymentPostingService.postPending());
        assertEquals("POSTED", jdbc.queryForObject(
                "SELECT status FROM payment_posting WHERE payment_id = ?", String.class, payment.paymentId()));
        assertEquals(journalId, jdbc.queryForObject(
                "SELECT journal_id FROM payment_posting WHERE payment_id = ?", String.class,
                payment.paymentId()));
        assertEquals(1, ledgerQuery.journalCount(paymentOperationKey(payment.paymentId())));
        assertEquals(3, ledgerQuery.entryCount(journalId));
    }

    /** M10：账本不可用时保留支付成功事实与待记账任务，恢复后补记。 */
    @Test
    void m10_ledgerUnavailableKeepsSuccessAndRecovers() {
        ledgerAvailability.setAvailable(false);
        try {
            PaymentView created = paymentService.createPayment(MERCHANT, "K-ORDER-M10", "ORDER-M10", 100L, "CNY");
            attemptSender.sendPending();
            assertEquals(0, paymentPostingService.postPending());

            PaymentView after = paymentService.getPayment(created.paymentId());
            assertEquals("SUCCEEDED", after.status());
            assertEquals("PENDING_POSTING", after.postingStatus());
            assertEquals("LEDGER_UNAVAILABLE", jdbc.queryForObject(
                    "SELECT status FROM payment_posting WHERE payment_id = ?", String.class, after.paymentId()));
        } finally {
            ledgerAvailability.setAvailable(true);
        }

        PaymentView recovery = paymentService.getPayment(
                paymentService.findByMerchantOrder(MERCHANT, "ORDER-M10").get(0).paymentId());
        assertEquals(1, paymentPostingService.postPending());
        assertEquals("POSTED", paymentService.getPayment(recovery.paymentId()).postingStatus());
        assertEquals(1, ledgerQuery.journalCount(paymentOperationKey(recovery.paymentId())));
        assertEquals(3, ledgerQuery.entryCount(jdbc.queryForObject(
                "SELECT journal_id FROM payment_posting WHERE payment_id = ?", String.class,
                recovery.paymentId())));
    }

    /** J1：收款凭证结构为借应收渠道、贷应付商户（净额）、贷手续费收入。 */
    @Test
    void paymentPostingCreatesChannelReceivablePayableAndFeeEntries() {
        PaymentView payment = succeededPayment(MERCHANT, "ORDER-J1", 100);
        String payable = accounts.merchantPayable(BOOK, MERCHANT, "CNY");
        String receivable = accounts.receivableChannel(BOOK, "CNY");
        String feeIncome = accounts.feeIncome(BOOK, "CNY");

        assertEquals(100, ledgerQuery.balance(receivable).postedBalance());
        assertEquals(99, ledgerQuery.balance(payable).postedBalance());
        assertEquals(1, ledgerQuery.balance(feeIncome).postedBalance());

        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT account_id, side, amount_minor FROM ledger_entry WHERE journal_id = "
                        + "(SELECT journal_id FROM payment_posting WHERE payment_id = ?) ORDER BY entry_id",
                payment.paymentId());
        assertEquals(3, entries.size());
        assertEquals(receivable, entries.get(0).get("account_id"));
        assertEquals("DEBIT", entries.get(0).get("side"));
        assertEquals(100L, ((Number) entries.get(0).get("amount_minor")).longValue());
        assertEquals(payable, entries.get(1).get("account_id"));
        assertEquals("CREDIT", entries.get(1).get("side"));
        assertEquals(99L, ((Number) entries.get(1).get("amount_minor")).longValue());
        assertEquals(feeIncome, entries.get(2).get("account_id"));
        assertEquals(1L, ((Number) entries.get(2).get("amount_minor")).longValue());
    }

    private PaymentView succeededPayment(String merchantId, String orderId, long amount) {
        PaymentView created = paymentService.createPayment(merchantId, "K-" + orderId, orderId, amount, "CNY");
        attemptSender.sendPending();
        paymentPostingService.postPending();
        PaymentView after = paymentService.getPayment(created.paymentId());
        assertEquals("SUCCEEDED", after.status(), "测试前置：支付必须成功");
        return after;
    }

    private String paymentOperationKey(String paymentId) {
        return BOOK + "|" + MERCHANT + "|PAYMENT_POSTING|" + paymentId;
    }

    private record RefundRepositoryPool(long refundedTotal, long reservedTotal) {
    }

    private RefundRepositoryPool poolOf(String paymentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT refunded_total, reserved_total FROM refund_pool WHERE payment_id = ?", paymentId);
        Map<String, Object> row = rows.get(0);
        return new RefundRepositoryPool(((Number) row.get("refunded_total")).longValue(),
                ((Number) row.get("reserved_total")).longValue());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
