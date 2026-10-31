package com.example.paymentcore.refund;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RefundRepository {

    public record RefundRow(String refundId, String paymentId, String merchantId, long amountMinor,
                            String currency, String status, String providerIdempotencyKey, int version) {
    }

    public record RefundIdempotencyRow(String refundId, String payloadDigest) {
    }

    public record ReservationRow(String refundId, String accountId, long amountMinor, String currency,
                                 String status) {
    }

    public record PoolRow(long refundableBase, long refundedTotal, long reservedTotal) {
    }

    public record SendContext(String refundId, String providerIdempotencyKey, long amountMinor, String currency,
                              String merchantOrderId) {
    }

    private final JdbcTemplate jdbc;

    public RefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RefundRow findRefund(String refundId) {
        List<RefundRow> rows = jdbc.query(
                "SELECT refund_id, payment_id, merchant_id, amount_minor, currency, status, "
                        + "provider_idempotency_key, version FROM refund_order WHERE refund_id = ?",
                (rs, i) -> new RefundRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8)),
                refundId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RefundView view(String refundId) {
        RefundRow row = findRefund(refundId);
        return row == null ? null : new RefundView(row.refundId(), row.paymentId(), row.merchantId(),
                row.amountMinor(), row.currency(), row.status(), row.version());
    }

    public List<RefundView> findByPayment(String paymentId, String merchantId) {
        return jdbc.query(
                "SELECT refund_id, payment_id, merchant_id, amount_minor, currency, status, version "
                        + "FROM refund_order WHERE payment_id = ? AND merchant_id = ? ORDER BY created_at",
                (rs, i) -> new RefundView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7)),
                paymentId, merchantId);
    }

    public RefundIdempotencyRow findIdempotency(String merchantId, String idempotencyKey) {
        List<RefundIdempotencyRow> rows = jdbc.query(
                "SELECT refund_id, payload_digest FROM refund_idempotency "
                        + "WHERE merchant_id = ? AND idempotency_key = ?",
                (rs, i) -> new RefundIdempotencyRow(rs.getString(1), rs.getString(2)),
                merchantId, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReservationRow loadReservation(String refundId) {
        List<ReservationRow> rows = jdbc.query(
                "SELECT refund_id, account_id, amount_minor, currency, status FROM refund_reservation "
                        + "WHERE refund_id = ?",
                (rs, i) -> new ReservationRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5)),
                refundId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PoolRow findPool(String paymentId) {
        List<PoolRow> rows = jdbc.query(
                "SELECT refundable_base, refunded_total, reserved_total FROM refund_pool WHERE payment_id = ?",
                (rs, i) -> new PoolRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)), paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SendContext loadSendContext(String refundId) {
        List<SendContext> rows = jdbc.query(
                "SELECT r.refund_id, r.provider_idempotency_key, r.amount_minor, r.currency, "
                        + "p.merchant_order_id FROM refund_order r "
                        + "JOIN payment_order p ON p.payment_id = r.payment_id WHERE r.refund_id = ?",
                (rs, i) -> new SendContext(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5)),
                refundId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
