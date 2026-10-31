package com.example.paymentcore.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class PaymentRepository {

    public record IdempotencyRow(String paymentId, String payloadDigest) {
    }

    public record AttemptContext(String attemptId, String paymentId, String operationId,
                                 String providerIdempotencyKey, String channelId, String channelAccount,
                                 long amountMinor, String currency, String merchantOrderId, String status) {
    }

    public record PaymentRow(String paymentId, String merchantId, String merchantOrderId, long amountMinor,
                             String currency, String status, String postingStatus) {
    }

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IdempotencyRow findIdempotency(String merchantId, String operationType, String namespace,
                                          String idempotencyKey) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT payment_id, payload_digest FROM merchant_idempotency "
                        + "WHERE merchant_id = ? AND operation_type = ? AND namespace = ? AND idempotency_key = ?",
                (rs, i) -> new IdempotencyRow(rs.getString(1), rs.getString(2)),
                merchantId, operationType, namespace, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PaymentView findPayment(String paymentId) {
        List<PaymentView> rows = jdbc.query(
                "SELECT p.payment_id, p.merchant_order_id, p.amount_minor, p.currency, p.status, "
                        + "p.posting_status, p.version, "
                        + "(SELECT a.status FROM channel_attempt a WHERE a.payment_id = p.payment_id "
                        + " ORDER BY a.created_at, a.attempt_id LIMIT 1) AS attempt_status "
                        + "FROM payment_order p WHERE p.payment_id = ?",
                (rs, i) -> new PaymentView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8)),
                paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<PaymentView> findByMerchantOrder(String merchantId, String merchantOrderId) {
        return jdbc.query(
                "SELECT p.payment_id, p.merchant_order_id, p.amount_minor, p.currency, p.status, "
                        + "p.posting_status, p.version, "
                        + "(SELECT a.status FROM channel_attempt a WHERE a.payment_id = p.payment_id "
                        + " ORDER BY a.created_at, a.attempt_id LIMIT 1) AS attempt_status "
                        + "FROM payment_order p WHERE p.merchant_id = ? AND p.merchant_order_id = ? "
                        + "ORDER BY p.created_at",
                (rs, i) -> new PaymentView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8)),
                merchantId, merchantOrderId);
    }

    public List<PaymentHistory> findHistory(String paymentId) {
        return jdbc.query(
                "SELECT history_id, status, detail, created_at FROM payment_event "
                        + "WHERE payment_id = ? ORDER BY history_id",
                (rs, i) -> new PaymentHistory(rs.getLong(1), rs.getString(2), rs.getString(3),
                        toInstant(rs.getTimestamp(4))),
                paymentId);
    }

    public AttemptContext loadAttemptContext(String attemptId) {
        List<AttemptContext> rows = jdbc.query(
                "SELECT a.attempt_id, a.payment_id, a.operation_id, a.provider_idempotency_key, "
                        + "a.channel_id, a.channel_account, a.status, "
                        + "p.amount_minor, p.currency, p.merchant_order_id "
                        + "FROM channel_attempt a JOIN payment_order p ON p.payment_id = a.payment_id "
                        + "WHERE a.attempt_id = ?",
                (rs, i) -> new AttemptContext(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(8),
                        rs.getString(9), rs.getString(10), rs.getString(7)),
                attemptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String currentAttemptStatus(String attemptId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM channel_attempt WHERE attempt_id = ?", String.class, attemptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PaymentRow loadPaymentRow(String paymentId) {
        List<PaymentRow> rows = jdbc.query(
                "SELECT payment_id, merchant_id, merchant_order_id, amount_minor, currency, status, "
                        + "posting_status FROM payment_order WHERE payment_id = ?",
                (rs, i) -> new PaymentRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)),
                paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int currentPaymentVersion(String paymentId) {
        Integer version = jdbc.queryForObject(
                "SELECT version FROM payment_order WHERE payment_id = ?", Integer.class, paymentId);
        return version == null ? 0 : version;
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
