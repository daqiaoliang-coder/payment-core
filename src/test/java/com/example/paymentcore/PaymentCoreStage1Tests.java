package com.example.paymentcore;

import com.example.paymentcore.channel.MockChannel;
import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.domain.ChannelScenario;
import com.example.paymentcore.outbox.OutboxPublisher;
import com.example.paymentcore.payment.AttemptSender;
import com.example.paymentcore.payment.PaymentService;
import com.example.paymentcore.payment.PaymentView;
import com.example.paymentcore.payment.RecoveryService;
import com.example.paymentcore.payment.TickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1 适用断言：P01/P02/P05/P06/P07/M06/M07。
 * 不依赖睡眠，全部通过同步驱动发送、查单与发布。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentCoreStage1Tests {

    private static final String MERCHANT = "M-001";

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private AttemptSender sender;
    @Autowired
    private RecoveryService recovery;
    @Autowired
    private OutboxPublisher publisher;
    @Autowired
    private TickService tickService;
    @Autowired
    private MockChannel mockChannel;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of("merchant_idempotency", "payment_order", "channel_attempt", "payment_fact",
                "payment_event", "outbox_record", "event_log", "mock_channel_scenario", "mock_channel_tx",
                "recovery_lease", "recovery_guard", "authority_scope", "late_effect", "approval_request",
                "security_audit", "beneficiary_account")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** P01：并发同键同参只产生一张支付单和一个尝试。 */
    @Test
    void p01_concurrentSameKeyCreatesSinglePayment() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentView>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return paymentService.createPayment(MERCHANT, "K-CONC", "ORDER-CONC", 1000L, "CNY");
                }));
            }
            start.countDown();

            Set<String> paymentIds = new HashSet<>();
            for (Future<PaymentView> future : futures) {
                paymentIds.add(future.get(30, TimeUnit.SECONDS).paymentId());
            }
            assertEquals(1, paymentIds.size(), "并发同键必须收敛到同一支付单");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, count("SELECT COUNT(*) FROM payment_order"));
        assertEquals(1, count("SELECT COUNT(*) FROM merchant_idempotency"));
        assertEquals(1, count("SELECT COUNT(*) FROM channel_attempt"));
        assertEquals(1, count("SELECT COUNT(*) FROM outbox_record"));
    }

    /** P02 + M06：同键异参返回 409，且不产生部分写入。 */
    @Test
    void p02_sameKeyDifferentAmountConflictsWithoutPartialRows() {
        PaymentView first = paymentService.createPayment(MERCHANT, "K-1", "ORDER-1", 1000L, "CNY");

        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> paymentService.createPayment(MERCHANT, "K-1", "ORDER-1", 2000L, "CNY"));
        assertEquals("idempotency_conflict", conflict.code());

        assertEquals(1, count("SELECT COUNT(*) FROM payment_order"));
        assertEquals(1, count("SELECT COUNT(*) FROM channel_attempt"));
        assertEquals(1, count("SELECT COUNT(*) FROM outbox_record"));
        assertEquals(first.paymentId(), paymentService.getPayment(first.paymentId()).paymentId());
    }

    /** M06 补充：换幂等键复用同一商户订单号仍被拒绝。 */
    @Test
    void merchantOrderCannotBeReusedByAnotherKey() {
        paymentService.createPayment(MERCHANT, "K-A", "ORDER-X", 1000L, "CNY");

        ApiConflictException conflict = assertThrows(ApiConflictException.class,
                () -> paymentService.createPayment(MERCHANT, "K-B", "ORDER-X", 1000L, "CNY"));
        assertEquals("merchant_order_conflict", conflict.code());
        assertEquals(1, count("SELECT COUNT(*) FROM payment_order"));
    }

    /** P05：渠道已扣款但响应超时，本地进入 UNKNOWN，不标记失败、不产生第二个尝试。 */
    @Test
    void p05_chargeThenTimeoutBecomesUnknown() {
        mockChannel.setScenario("ORDER-5", ChannelScenario.CHARGE_THEN_TIMEOUT);
        PaymentView created = paymentService.createPayment(MERCHANT, "K-5", "ORDER-5", 2500L, "CNY");
        assertEquals("CREATED", created.attemptStatus());

        sender.sendPending();

        PaymentView after = paymentService.getPayment(created.paymentId());
        assertEquals("ACCEPTED", after.status(), "超时不能关闭支付单");
        assertEquals("UNKNOWN", after.attemptStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM channel_attempt"));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM mock_channel_tx WHERE provider_idempotency_key = "
                        + "(SELECT provider_idempotency_key FROM channel_attempt)", String.class));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_fact"));
    }

    /** P06：查单找回成功证据，补录事实并推进支付单。 */
    @Test
    void p06_recoveryConfirmsSuccessWithSingleFact() {
        mockChannel.setScenario("ORDER-6", ChannelScenario.CHARGE_THEN_TIMEOUT);
        PaymentView created = paymentService.createPayment(MERCHANT, "K-6", "ORDER-6", 2500L, "CNY");
        sender.sendPending();

        recovery.recoverOpenAttempts();
        publisher.publishPending();

        PaymentView after = paymentService.getPayment(created.paymentId());
        assertEquals("SUCCEEDED", after.status());
        assertEquals("SUCCEEDED", after.attemptStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM payment_fact"));
        assertEquals(1, count("SELECT COUNT(*) FROM event_log WHERE event_type = 'PAYMENT_SUCCEEDED'"));
        // CREATED -> UNKNOWN -> SUCCEEDED，历史追加写入且不覆盖
        assertEquals(3, paymentService.history(created.paymentId()).size());
    }

    /** P07：渠道明确拒绝才进入失败终态。 */
    @Test
    void p07_declineBecomesFailed() {
        mockChannel.setScenario("ORDER-7", ChannelScenario.DECLINE);
        PaymentView created = paymentService.createPayment(MERCHANT, "K-7", "ORDER-7", 1200L, "CNY");

        sender.sendPending();

        PaymentView after = paymentService.getPayment(created.paymentId());
        assertEquals("FAILED", after.status());
        assertEquals("FAILED", after.attemptStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM payment_fact"));
    }

    /** M07：重复驱动发送/核查/发布不产生第二笔事实或第二条投递日志。 */
    @Test
    void m07_repeatedTickKeepsSingleFactAndSingleDelivery() {
        PaymentView created = paymentService.createPayment(MERCHANT, "K-8", "ORDER-8", 3000L, "CNY");

        tickService.tick();
        tickService.tick();

        PaymentView after = paymentService.getPayment(created.paymentId());
        assertEquals("SUCCEEDED", after.status());
        assertEquals(1, count("SELECT COUNT(*) FROM payment_fact"));
        assertEquals(1, count("SELECT COUNT(*) FROM event_log WHERE event_type = 'PAYMENT_SUCCEEDED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM mock_channel_tx"));
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_record WHERE status = 'PENDING'"));
    }

    /** 处理中场景：未获证据前不确认成功，查单后才补录事实。 */
    @Test
    void processingThenSuccessOnlyConfirmsAfterQuery() {
        mockChannel.setScenario("ORDER-9", ChannelScenario.PROCESSING_THEN_SUCCESS);
        PaymentView created = paymentService.createPayment(MERCHANT, "K-9", "ORDER-9", 900L, "CNY");

        sender.sendPending();
        assertEquals("PROCESSING", paymentService.getPayment(created.paymentId()).attemptStatus());
        assertEquals("ACCEPTED", paymentService.getPayment(created.paymentId()).status());

        recovery.recoverOpenAttempts();
        assertEquals("SUCCEEDED", paymentService.getPayment(created.paymentId()).status());
        assertTrue(count("SELECT COUNT(*) FROM payment_fact") == 1);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
