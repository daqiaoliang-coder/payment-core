package com.example.paymentcore.payment;

import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.common.DuplicateIdempotencyKeyException;
import com.example.paymentcore.common.DuplicateMerchantOrderException;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.domain.AttemptStatus;
import com.example.paymentcore.domain.OutboxStatus;
import com.example.paymentcore.domain.PaymentStatus;
import com.example.paymentcore.domain.PostingStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 事务边界实现：
 * T1 收款受理 = 幂等绑定 + 支付单 + 尝试/操作身份 + 发送 outbox，同一事务，不含渠道调用。
 * T4 事实确认 = 尝试状态 + 资金事实 + 支付投影 + 状态历史 + outbox，同一事务。
 */
@Service
public class PaymentTransactions {

    private static final String CHANNEL_ID = "MOCK";
    private static final String CHANNEL_ACCOUNT = "MOCK-ACC-001";
    private static final String FACT_CHARGE = "CHARGE";

    private final JdbcTemplate jdbc;
    private final PaymentRepository repository;
    private final ObjectMapper objectMapper;

    public PaymentTransactions(JdbcTemplate jdbc, PaymentRepository repository, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentView create(String merchantId, String operationType, String namespace, String idempotencyKey,
                              String merchantOrderId, long amountMinor, String currency, String payloadDigest) {
        String paymentId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        String providerKey = "pk-" + operationId;

        try {
            jdbc.update("INSERT INTO merchant_idempotency(merchant_id, operation_type, namespace, "
                            + "idempotency_key, payload_digest, payment_id) VALUES (?, ?, ?, ?, ?, ?)",
                    merchantId, operationType, namespace, idempotencyKey, payloadDigest, paymentId);
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException("幂等键已被其他请求占用");
        }

        try {
            jdbc.update("INSERT INTO payment_order(payment_id, merchant_id, merchant_order_id, amount_minor, "
                            + "currency, status, posting_status, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    paymentId, merchantId, merchantOrderId, amountMinor, currency,
                    PaymentStatus.ACCEPTED.name(), PostingStatus.PENDING_POSTING.name(), 0);
        } catch (DuplicateKeyException e) {
            throw new DuplicateMerchantOrderException("商户订单号已绑定其他支付单");
        }

        jdbc.update("INSERT INTO channel_attempt(attempt_id, payment_id, operation_id, channel_id, "
                        + "channel_account, provider_idempotency_key, status, lease_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                attemptId, paymentId, operationId, CHANNEL_ID, CHANNEL_ACCOUNT, providerKey,
                AttemptStatus.CREATED.name(), 0);

        appendHistory(paymentId, AttemptStatus.CREATED.name(), "支付单已受理，尚未越过渠道发送边界");
        enqueue(paymentId, 0, "PAYMENT_ACCEPTED",
                payload(paymentId, merchantOrderId, PaymentStatus.ACCEPTED.name(), amountMinor, currency, null));

        PaymentView view = repository.findPayment(paymentId);
        if (view == null) {
            throw new IllegalStateException("支付单创建后无法读取");
        }
        return view;
    }

    @Transactional
    public boolean confirmSuccess(String attemptId, ChannelResult evidence) {
        PaymentRepository.AttemptContext ctx = requireAttempt(attemptId);
        if (evidence.providerReference() == null) {
            throw new EvidenceConflictException("成功证据缺少渠道引用");
        }
        if (evidence.amountMinor() != ctx.amountMinor()
                || !ctx.currency().equalsIgnoreCase(evidence.currency())) {
            // 金额或币种不匹配属于差错，不能确认成功
            throw new EvidenceConflictException("渠道成功证据的金额或币种与支付单不一致");
        }

        int updated = jdbc.update("UPDATE channel_attempt SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE attempt_id = ? AND status IN (?, ?, ?)",
                AttemptStatus.SUCCEEDED.name(), attemptId,
                AttemptStatus.SUBMITTED.name(), AttemptStatus.PROCESSING.name(), AttemptStatus.UNKNOWN.name());
        if (updated == 0) {
            String current = repository.currentAttemptStatus(attemptId);
            if (AttemptStatus.SUCCEEDED.name().equals(current)) {
                return false; // 重复的同一事实
            }
            throw new EvidenceConflictException("尝试处于 " + current + "，不接受成功证据");
        }

        try {
            jdbc.update("INSERT INTO payment_fact(fact_id, operation_id, fact_type, provider_reference, "
                            + "amount_minor, currency) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), ctx.operationId(), FACT_CHARGE, evidence.providerReference(),
                    ctx.amountMinor(), ctx.currency());
        } catch (DuplicateKeyException e) {
            return false; // 同一资金事实已确认，不重复产生业务效果
        }

        int paymentUpdated = jdbc.update("UPDATE payment_order SET status = ?, posting_status = ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE payment_id = ? AND status = ?",
                PaymentStatus.SUCCEEDED.name(), PostingStatus.PENDING_POSTING.name(),
                ctx.paymentId(), PaymentStatus.ACCEPTED.name());
        if (paymentUpdated > 0) {
            int version = repository.currentPaymentVersion(ctx.paymentId());
            jdbc.update("INSERT INTO payment_posting(payment_id, status, attempts) VALUES (?, ?, 0)",
                    ctx.paymentId(), com.example.paymentcore.domain.PostingJobStatus.PENDING.name());
            appendHistory(ctx.paymentId(), AttemptStatus.SUCCEEDED.name(), "可信渠道证据确认收款成功");
            enqueue(ctx.paymentId(), version, "PAYMENT_SUCCEEDED",
                    payload(ctx.paymentId(), ctx.merchantOrderId(), PaymentStatus.SUCCEEDED.name(),
                            ctx.amountMinor(), ctx.currency(), evidence.providerReference()));
        }
        return true;
    }

    @Transactional
    public boolean fail(String attemptId, String detail) {
        PaymentRepository.AttemptContext ctx = requireAttempt(attemptId);
        int updated = jdbc.update("UPDATE channel_attempt SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE attempt_id = ? AND status IN (?, ?, ?)",
                AttemptStatus.FAILED.name(), attemptId,
                AttemptStatus.SUBMITTED.name(), AttemptStatus.PROCESSING.name(), AttemptStatus.UNKNOWN.name());
        if (updated == 0) {
            String current = repository.currentAttemptStatus(attemptId);
            if (AttemptStatus.FAILED.name().equals(current)) {
                return false;
            }
            throw new EvidenceConflictException("尝试处于 " + current + "，无法转为失败");
        }

        // 只有同一支付单没有其他在途或未知尝试时，才允许关闭支付单
        int openAttempts = jdbc.queryForObject("SELECT COUNT(*) FROM channel_attempt "
                + "WHERE payment_id = ? AND status IN (?, ?, ?, ?)", Integer.class,
                ctx.paymentId(), AttemptStatus.CREATED.name(), AttemptStatus.SUBMITTED.name(),
                AttemptStatus.PROCESSING.name(), AttemptStatus.UNKNOWN.name());
        if (openAttempts == 0) {
            int paymentUpdated = jdbc.update("UPDATE payment_order SET status = ?, "
                            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE payment_id = ? AND status = ?",
                    PaymentStatus.FAILED.name(), ctx.paymentId(), PaymentStatus.ACCEPTED.name());
            if (paymentUpdated > 0) {
                int version = repository.currentPaymentVersion(ctx.paymentId());
                appendHistory(ctx.paymentId(), AttemptStatus.FAILED.name(), detail);
                enqueue(ctx.paymentId(), version, "PAYMENT_FAILED",
                        payload(ctx.paymentId(), ctx.merchantOrderId(), PaymentStatus.FAILED.name(),
                                ctx.amountMinor(), ctx.currency(), null));
            }
        }
        return true;
    }

    @Transactional
    public boolean markUnknown(String attemptId, String detail) {
        PaymentRepository.AttemptContext ctx = requireAttempt(attemptId);
        int updated = jdbc.update("UPDATE channel_attempt SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE attempt_id = ? AND status IN (?, ?)",
                AttemptStatus.UNKNOWN.name(), attemptId,
                AttemptStatus.SUBMITTED.name(), AttemptStatus.PROCESSING.name());
        if (updated > 0) {
            // UNKNOWN 是本地核查状态，支付单保持 ACCEPTED，禁止以新尝试重新扣款
            appendHistory(ctx.paymentId(), AttemptStatus.UNKNOWN.name(), detail);
        }
        return updated > 0;
    }

    @Transactional
    public boolean markProcessing(String attemptId, String detail) {
        PaymentRepository.AttemptContext ctx = requireAttempt(attemptId);
        int updated = jdbc.update("UPDATE channel_attempt SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE attempt_id = ? AND status IN (?, ?)",
                AttemptStatus.PROCESSING.name(), attemptId,
                AttemptStatus.SUBMITTED.name(), AttemptStatus.UNKNOWN.name());
        if (updated > 0) {
            appendHistory(ctx.paymentId(), AttemptStatus.PROCESSING.name(), detail);
        }
        return updated > 0;
    }

    private PaymentRepository.AttemptContext requireAttempt(String attemptId) {
        PaymentRepository.AttemptContext ctx = repository.loadAttemptContext(attemptId);
        if (ctx == null) {
            throw new IllegalArgumentException("未知渠道尝试: " + attemptId);
        }
        return ctx;
    }

    private void appendHistory(String paymentId, String status, String detail) {
        jdbc.update("INSERT INTO payment_event(payment_id, status, detail) VALUES (?, ?, ?)",
                paymentId, status, detail);
    }

    private void enqueue(String paymentId, int aggregateVersion, String eventType, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件载荷序列化失败", e);
        }
        jdbc.update("INSERT INTO outbox_record(event_id, aggregate_id, aggregate_version, event_type, "
                        + "payload, status, publish_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventId, paymentId, aggregateVersion, eventType, json, OutboxStatus.PENDING.name(), 0);
    }

    private Map<String, Object> payload(String paymentId, String merchantOrderId, String status,
                                        long amountMinor, String currency, String providerReference) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId);
        data.put("merchantOrderId", merchantOrderId);
        data.put("status", status);
        data.put("amountMinor", amountMinor);
        data.put("currency", currency);
        if (providerReference != null) {
            data.put("providerReference", providerReference);
        }
        return data;
    }
}
