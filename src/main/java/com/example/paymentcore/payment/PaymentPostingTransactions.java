package com.example.paymentcore.payment;

import com.example.paymentcore.domain.OutboxStatus;
import com.example.paymentcore.domain.PostingJobStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 记账状态落地：支付投影与待记账任务同事务更新，失败不改变支付成功事实。 */
@Service
public class PaymentPostingTransactions {

    private final JdbcTemplate jdbc;
    private final PaymentRepository repository;
    private final ObjectMapper objectMapper;

    public PaymentPostingTransactions(JdbcTemplate jdbc, PaymentRepository repository,
                                      ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void markPosted(String paymentId, String journalId) {
        int updated = jdbc.update("UPDATE payment_posting SET status = ?, journal_id = ?, "
                        + "attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE payment_id = ? AND status <> ?",
                PostingJobStatus.POSTED.name(), journalId, paymentId, PostingJobStatus.POSTED.name());
        if (updated == 0) {
            return;
        }
        int paymentUpdated = jdbc.update("UPDATE payment_order SET posting_status = ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE payment_id = ? AND posting_status = ?",
                com.example.paymentcore.domain.PostingStatus.POSTED.name(), paymentId,
                com.example.paymentcore.domain.PostingStatus.PENDING_POSTING.name());
        if (paymentUpdated > 0) {
            int version = repository.currentPaymentVersion(paymentId);
            jdbc.update("INSERT INTO payment_event(payment_id, status, detail) VALUES (?, ?, ?)",
                    paymentId, "POSTED", "账务凭证已提交");
            enqueue(paymentId, version, "PAYMENT_POSTED", journalId);
        }
    }

    public void markLedgerUnavailable() {
        jdbc.update("UPDATE payment_posting SET status = ?, attempts = attempts + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE status <> ?",
                PostingJobStatus.LEDGER_UNAVAILABLE.name(), PostingJobStatus.POSTED.name());
    }

    private void enqueue(String paymentId, int version, String eventType, String journalId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", paymentId);
        payload.put("journalId", journalId);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记账事件序列化失败", e);
        }
        jdbc.update("INSERT INTO outbox_record(event_id, aggregate_id, aggregate_version, event_type, "
                        + "payload, status, publish_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), paymentId, version, eventType, json,
                OutboxStatus.PENDING.name(), 0);
    }
}
