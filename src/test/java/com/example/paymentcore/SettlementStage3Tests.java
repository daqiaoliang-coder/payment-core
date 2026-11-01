package com.example.paymentcore;

import com.example.paymentcore.bank.MockBank;
import com.example.paymentcore.channel.MockChannel;
import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiNotFoundException;
import com.example.paymentcore.domain.BankScenario;
import com.example.paymentcore.domain.ChannelScenario;
import com.example.paymentcore.ledger.BalanceProjectionService;
import com.example.paymentcore.ledger.LedgerAccountService;
import com.example.paymentcore.ledger.LedgerAvailability;
import com.example.paymentcore.ledger.LedgerConstants;
import com.example.paymentcore.ledger.LedgerQuery;
import com.example.paymentcore.payment.PaymentService;
import com.example.paymentcore.payment.PaymentView;
import com.example.paymentcore.payment.TickService;
import com.example.paymentcore.recon.DifferenceView;
import com.example.paymentcore.recon.ReportView;
import com.example.paymentcore.recon.ReconReportRequest;
import com.example.paymentcore.recon.ReconService;
import com.example.paymentcore.recon.StatementView;
import com.example.paymentcore.refund.RefundService;
import com.example.paymentcore.refund.RefundView;
import com.example.paymentcore.settlement.BatchView;
import com.example.paymentcore.settlement.PayoutRecoveryService;
import com.example.paymentcore.settlement.PayoutSender;
import com.example.paymentcore.settlement.PayoutService;
import com.example.paymentcore.settlement.PayoutView;
import com.example.paymentcore.settlement.SettlementItemView;
import com.example.paymentcore.settlement.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 3 适用断言：S01—S10。
 * 全部使用模拟渠道与模拟银行；不宣称真实清分、真实资金或真实对账能力。
 */
@SpringBootTest
@ActiveProfiles("test")
class SettlementStage3Tests {

    private static final String MERCHANT = "M-STAGE3";
    private static final String BOOK = LedgerConstants.BOOK_ID;
    private static final String FEE_V1 = "fee-v1";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private SettlementService settlement;
    @Autowired
    private PayoutService payoutService;
    @Autowired
    private PayoutSender payoutSender;
    @Autowired
    private PayoutRecoveryService payoutRecovery;
    @Autowired
    private ReconService reconService;
    @Autowired
    private TickService tickService;
    @Autowired
    private MockChannel mockChannel;
    @Autowired
    private MockBank mockBank;
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
        for (String table : List.of("recon_difference", "recon_record", "recon_page", "recon_report",
                "bank_allocation", "bank_statement", "payout_event", "payout_order", "settlement_item",
                "clearing_entry", "settlement_batch", "mock_bank_scenario", "mock_bank_tx",
                "merchant_idempotency", "payment_order", "channel_attempt", "payment_fact", "payment_event",
                "outbox_record", "event_log", "mock_channel_scenario", "mock_channel_tx", "mock_channel_refund_tx",
                "refund_order", "refund_idempotency", "refund_pool", "refund_reservation", "reservation_event",
                "refund_event", "payment_posting", "ledger_entry", "ledger_journal", "ledger_business_operation",
                "correction_link", "balance_projection", "account_balance", "ledger_account",
                "recovery_lease", "recovery_guard", "authority_scope", "late_effect", "approval_request",
                "security_audit", "beneficiary_account")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM fee_rule WHERE rule_version <> ?", FEE_V1);
        jdbc.update("MERGE INTO fee_rule KEY(rule_version) VALUES (?, 100, 0, TRUE, CURRENT_TIMESTAMP)",
                FEE_V1);
        ledgerAvailability.setAvailable(true);
    }

    /** S01：同一应结明细被两个批次并发消费时，只有一方成功。 */
    @Test
    void s01_sameClearingItemCannotBeConsumedTwice() throws Exception {
        PaymentView payment = successfulPayment("S01-ORDER", 100L);
        BatchView first = settlement.openBatch(MERCHANT, "CNY", FEE_V1);
        BatchView second = settlement.openBatch(MERCHANT, "CNY", FEE_V1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(addTask(start, first.batchId(), payment.paymentId())));
            futures.add(pool.submit(addTask(start, second.batchId(), payment.paymentId())));
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(1, succeeded, "同一支付只能被一个有效批次消费");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, count("SELECT COUNT(*) FROM clearing_entry WHERE item_key = 'P:" + payment.paymentId() + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM settlement_item"));
    }

    /** S02：出款超时进入 UNKNOWN，保留占额与原单，查单后收敛为成功。 */
    @Test
    void s02_payoutTimeoutKeepsReservationAndOriginalIntent() {
        PaymentView payment = successfulPayment("S02-ORDER", 100L);
        BatchView batch = settlement.openBatch(MERCHANT, "CNY", FEE_V1);
        settlement.addPayment(batch.batchId(), payment.paymentId());
        batch = settlement.freeze(batch.batchId());
        assertEquals(99L, batch.netMinor());

        PayoutView created = payoutService.createPayout(batch.batchId());
        assertEquals("CREATED", created.status());
        String payable = accounts.merchantPayable(BOOK, MERCHANT, "CNY");
        assertEquals(99L, ledgerQuery.balance(payable).reservedTotal());

        mockBank.setScenario(MERCHANT, BankScenario.TIMEOUT_AFTER_DEBIT);
        payoutSender.sendPending();

        PayoutView unknown = payoutService.getPayout(created.payoutId());
        assertEquals("UNKNOWN", unknown.status());
        assertEquals(99L, ledgerQuery.balance(payable).reservedTotal(), "UNKNOWN 不得释放占额");
        assertEquals(1, count("SELECT COUNT(*) FROM payout_order"));
        assertEquals(created.payoutId(), payoutService.createPayout(batch.batchId()).payoutId(),
                "超时后不得换单号重发");

        payoutRecovery.recoverOpenPayouts();
        PayoutView settled = payoutService.getPayout(created.payoutId());
        assertEquals("SUCCEEDED", settled.status());
        assertNotNull(settled.journalId());
        assertEquals(0L, ledgerQuery.balance(payable).reservedTotal());
        assertEquals("PAID", settlement.getBatch(batch.batchId()).status());
    }

    /** S03：渠道标记已结算不等于商户到账，不自动形成出款或付款凭证。 */
    @Test
    void s03_channelSettlementDoesNotProduceMerchantPayout() {
        PaymentView payment = successfulPayment("S03-ORDER", 100L);
        BatchView batch = settlement.openBatch(MERCHANT, "CNY", FEE_V1);
        settlement.addPayment(batch.batchId(), payment.paymentId());
        batch = settlement.freeze(batch.batchId());

        ReportView report = reconService.importReport(report("chan-1", "CHANNEL_SETTLEMENT",
                List.of(new ReconReportRequest.Record("batch-1", "CHANNEL_SETTLEMENT", null, 98L))));
        assertEquals("IMPORTED", report.status());

        assertEquals("FROZEN", settlement.getBatch(batch.batchId()).status());
        assertEquals(0, count("SELECT COUNT(*) FROM payout_order"));
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_journal WHERE operation_key LIKE '%SETTLEMENT_PAYOUT%'"));
    }

    /** S04：限定口径下 100 收款、20 退款、2 渠道费同批净额为 78。 */
    @Test
    void s04_sameBatchNetAmountEquals78() {
        jdbc.update("MERGE INTO fee_rule KEY(rule_version) VALUES ('fee-v2', 200, 0, TRUE, CURRENT_TIMESTAMP)");
        PaymentView payment = successfulPayment("S04-ORDER", 100L);
        RefundView refund = successfulRefund(payment.paymentId(), "S04-RF", 20L);

        BatchView batch = settlement.openBatch(MERCHANT, "CNY", "fee-v2");
        settlement.addPayment(batch.batchId(), payment.paymentId());
        settlement.addRefund(batch.batchId(), refund.refundId());
        batch = settlement.freeze(batch.batchId());

        assertEquals(100L, batch.grossMinor());
        assertEquals(20L, batch.refundMinor());
        assertEquals(2L, batch.feeMinor());
        assertEquals(78L, batch.netMinor());
        assertEquals(0, count("SELECT COUNT(*) FROM bank_statement"), "银行到账仍需独立证据");
    }

    /** S05：退款落在下一批次时，两批分别解释，不强迫当前批匹配 78。 */
    @Test
    void s05_refundInNextBatchIsExplainedSeparately() {
        PaymentView payment = successfulPayment("S05-ORDER", 100L);
        BatchView first = settlement.openBatch(MERCHANT, "CNY", FEE_V1);
        settlement.addPayment(first.batchId(), payment.paymentId());
        first = settlement.freeze(first.batchId());
        assertEquals(99L, first.netMinor());

        RefundView refund = successfulRefund(payment.paymentId(), "S05-RF", 20L);
        BatchView second = settlement.openBatch(MERCHANT, "CNY", FEE_V1);
        settlement.addRefund(second.batchId(), refund.refundId());
        final BatchView frozenSecond = settlement.freeze(second.batchId());

        assertEquals(99L, first.netMinor());
        assertEquals(-20L, frozenSecond.netMinor());
        assertEquals("FROZEN", first.status());
        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> payoutService.createPayout(frozenSecond.batchId()));
        assertEquals("settlement_net_not_payable", conflict.code());
    }

    /** S06：同文件重投返回原报告；修订报告只新增文件版本，业务明细不重复入账。 */
    @Test
    void s06_reportFileAndBusinessRecordAreDeduplicatedSeparately() {
        PaymentView payment = successfulPayment("S06-ORDER", 100L);
        ReportView first = reconService.importReport(report("digest-v1", "PAYMENT",
                List.of(new ReconReportRequest.Record("row-1", "PAYMENT", payment.paymentId(), 100L))));
        ReportView replay = reconService.importReport(report("digest-v1", "PAYMENT",
                List.of(new ReconReportRequest.Record("row-1", "PAYMENT", payment.paymentId(), 100L))));
        assertEquals(first.reportId(), replay.reportId(), "同文件重投必须返回原报告");

        ReportView revised = reconService.importReport(report("digest-v2", "PAYMENT",
                List.of(new ReconReportRequest.Record("row-1", "PAYMENT", payment.paymentId(), 100L))));
        assertTrue(!first.reportId().equals(revised.reportId()), "修订报告是新证据版本");
        assertEquals(1, count("SELECT COUNT(*) FROM recon_record WHERE record_key = 'row-1'"));
        assertEquals(2, count("SELECT COUNT(*) FROM recon_report"));
    }

    /** S07：缺页、空文件与未到文件必须区分，不能用零交易替代数据缺失。 */
    @Test
    void s07_missingPagesEmptyFileAndAbsentFileAreDistinct() {
        ReconReportRequest incomplete = new ReconReportRequest("provider-a", "PAYMENT",
                PERIOD_START, PERIOD_END, "digest-incomplete", 3, 0L, "CNY",
                List.of(new ReconReportRequest.Page(1, "p1", List.of())));
        assertEquals("INCOMPLETE", reconService.importReport(incomplete).status());

        ReconReportRequest empty = new ReconReportRequest("provider-a", "PAYMENT",
                PERIOD_START, PERIOD_END, "digest-empty", 1, 0L, "CNY",
                List.of(new ReconReportRequest.Page(1, "p1", List.of())));
        assertEquals("EMPTY", reconService.importReport(empty).status());

        assertThrows(ApiNotFoundException.class, () -> reconService.getReport("not-imported"));
    }

    /** S08：汇总相等但逐笔差错不能关闭。 */
    @Test
    void s08_equalTotalsStillLeavesLineLevelDifferencesOpen() {
        PaymentView first = successfulPayment("S08-ORDER-A", 100L);
        PaymentView second = successfulPayment("S08-ORDER-B", 100L);

        ReconReportRequest request = new ReconReportRequest("provider-a", "PAYMENT",
                PERIOD_START, PERIOD_END, "digest-s08", 1, 0L, "CNY",
                List.of(new ReconReportRequest.Page(1, "p1", List.of(
                        new ReconReportRequest.Record("row-a", "PAYMENT", first.paymentId(), 100L),
                        new ReconReportRequest.Record("row-ghost", "PAYMENT", "ghost-payment", 100L)))));
        ReportView report = reconService.importReport(request);
        assertEquals("IMPORTED", report.status());
        assertEquals(200L, report.totalMinor());

        List<DifferenceView> differences = reconService.differences(report.reportId());
        assertEquals(2, differences.size());
        assertTrue(differences.stream().allMatch(d -> "OPEN".equals(d.status())));
        assertTrue(differences.stream().anyMatch(d -> "EXTRA_IN_REPORT".equals(d.differenceType())));
        assertTrue(differences.stream().anyMatch(d -> "MISSING_IN_REPORT".equals(d.differenceType())
                && d.recordKey().equals(second.paymentId())));
    }

    /** S09：一笔银行流水可分配多笔明细，超额与重复分配被拒绝。 */
    @Test
    void s09_bankStatementAllocationPreservesRemainingAmount() {
        PaymentView first = successfulPayment("S09-ORDER-A", 50L);
        PaymentView second = successfulPayment("S09-ORDER-B", 50L);
        PaymentView third = successfulPayment("S09-ORDER-C", 50L);
        BatchView batch = settlement.openBatch(MERCHANT, "CNY", FEE_V1);
        SettlementItemView itemA = settlement.addPayment(batch.batchId(), first.paymentId());
        SettlementItemView itemB = settlement.addPayment(batch.batchId(), second.paymentId());
        SettlementItemView itemC = settlement.addPayment(batch.batchId(), third.paymentId());

        StatementView statement = reconService.importStatement("bank-a", "REF-1", "IN", 100L, "CNY");
        StatementView partial = reconService.allocate(statement.statementId(), itemA.entryId(), 50L);
        assertEquals(50L, partial.allocatedMinor());
        assertEquals("PARTIAL", partial.status());

        StatementView full = reconService.allocate(statement.statementId(), itemB.entryId(), 50L);
        assertEquals(100L, full.allocatedMinor());
        assertEquals("ALLOCATED", full.status());

        ApiConflictException duplicate = assertThrows(ApiConflictException.class,
                () -> reconService.allocate(statement.statementId(), itemA.entryId(), 10L));
        assertEquals("duplicate_bank_allocation", duplicate.code());

        ApiConflictException exhausted = assertThrows(ApiConflictException.class,
                () -> reconService.allocate(statement.statementId(), itemC.entryId(), 1L));
        assertEquals("bank_statement_exhausted", exhausted.code());
    }

    /** S10：出款成功后退回，追加修正凭证并重新评估应付，不覆盖原成功历史。 */
    @Test
    void s10_payoutReturnAddsCorrectionAndRestoresPayable() {
        PaymentView payment = successfulPayment("S10-ORDER", 100L);
        BatchView batch = settlement.openBatch(MERCHANT, "CNY", FEE_V1);
        settlement.addPayment(batch.batchId(), payment.paymentId());
        batch = settlement.freeze(batch.batchId());

        PayoutView payout = payoutService.createPayout(batch.batchId());
        payoutSender.sendPending();
        PayoutView succeeded = payoutService.getPayout(payout.payoutId());
        assertEquals("SUCCEEDED", succeeded.status());
        assertEquals("PAID", settlement.getBatch(batch.batchId()).status());

        String payable = accounts.merchantPayable(BOOK, MERCHANT, "CNY");
        assertEquals(0L, ledgerQuery.balance(payable).postedBalance());

        PayoutView returned = payoutService.markReturned(payout.payoutId(), "银行退回资金", "treasury-lead");
        assertEquals("RETURNED", returned.status());
        assertEquals("RETURNED", settlement.getBatch(batch.batchId()).status());
        assertEquals(99L, ledgerQuery.balance(payable).postedBalance(), "修正凭证应恢复应付余额");
        assertEquals(1, count("SELECT COUNT(*) FROM correction_link"));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_journal WHERE kind = 'CORRECTION'"));
        assertEquals(1, count("SELECT COUNT(*) FROM payout_event WHERE status = 'RETURNED'"));
        assertNotNull(payoutService.getPayout(payout.payoutId()).journalId());
    }

    private Callable<Boolean> addTask(CountDownLatch start, String batchId, String paymentId) {
        return () -> {
            start.await();
            try {
                settlement.addPayment(batchId, paymentId);
                return true;
            } catch (ApiConflictException e) {
                return false;
            }
        };
    }

    private PaymentView successfulPayment(String merchantOrderId, long amountMinor) {
        PaymentView payment = paymentService.createPayment(MERCHANT, "K-" + merchantOrderId, merchantOrderId,
                amountMinor, "CNY");
        tickService.tick();
        assertEquals("SUCCEEDED", paymentService.getPayment(payment.paymentId()).status());
        return paymentService.getPayment(payment.paymentId());
    }

    private RefundView successfulRefund(String paymentId, String key, long amountMinor) {
        RefundView refund = refundService.createRefund(MERCHANT, key, paymentId, amountMinor);
        tickService.tick();
        assertEquals("SUCCEEDED", refundService.getRefund(refund.refundId()).status());
        return refundService.getRefund(refund.refundId());
    }

    private ReconReportRequest report(String fileDigest, String recordType,
                                      List<ReconReportRequest.Record> records) {
        return new ReconReportRequest("provider-a", "PAYMENT", PERIOD_START, PERIOD_END, fileDigest, 1, 0L,
                "CNY", List.of(new ReconReportRequest.Page(1, "page-1", records)));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static final Instant PERIOD_START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-10-01T23:59:59Z");
}
