package com.example.paymentcore.settlement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SettlementRepository {

    public record BatchRow(String batchId, String merchantId, String currency, String ruleVersion, String status,
                           long grossMinor, long refundMinor, long feeMinor, long netMinor, int itemCount,
                           int version) {
    }

    public record EntryRow(String entryId, String itemKey, String itemType, String paymentId, String refundId,
                           String merchantId, String currency, long grossMinor, long feeMinor, long netMinor,
                           String ruleVersion, String status) {
    }

    public record PayoutRow(String payoutId, String batchId, String merchantId, long amountMinor, String currency,
                            String status, String providerIdempotencyKey, String bankReference, String journalId,
                            int version) {
    }

    public record PayoutContext(String payoutId, String providerIdempotencyKey, String merchantId,
                                long amountMinor, String currency) {
    }

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BatchRow findBatch(String batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT batch_id, merchant_id, currency, rule_version, status, gross_minor, refund_minor, "
                        + "fee_minor, net_minor, item_count, version FROM settlement_batch WHERE batch_id = ?",
                (rs, i) -> new BatchRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                        rs.getInt(10), rs.getInt(11)),
                batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public BatchView batchView(String batchId) {
        BatchRow row = findBatch(batchId);
        return row == null ? null : new BatchView(row.batchId(), row.merchantId(), row.currency(),
                row.ruleVersion(), row.status(), row.grossMinor(), row.refundMinor(), row.feeMinor(),
                row.netMinor(), row.itemCount(), row.version());
    }

    public EntryRow findEntryByItemKey(String itemKey) {
        List<EntryRow> rows = jdbc.query(
                "SELECT entry_id, item_key, item_type, payment_id, refund_id, merchant_id, currency, gross_minor, "
                        + "fee_minor, net_minor, rule_version, status FROM clearing_entry WHERE item_key = ?",
                (rs, i) -> mapEntry(rs), itemKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public EntryRow findEntry(String entryId) {
        List<EntryRow> rows = jdbc.query(
                "SELECT entry_id, item_key, item_type, payment_id, refund_id, merchant_id, currency, gross_minor, "
                        + "fee_minor, net_minor, rule_version, status FROM clearing_entry WHERE entry_id = ?",
                (rs, i) -> mapEntry(rs), entryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<EntryRow> entriesByMerchant(String merchantId, String currency) {
        return jdbc.query(
                "SELECT entry_id, item_key, item_type, payment_id, refund_id, merchant_id, currency, gross_minor, "
                        + "fee_minor, net_minor, rule_version, status FROM clearing_entry "
                        + "WHERE merchant_id = ? AND currency = ? ORDER BY created_at, entry_id",
                (rs, i) -> mapEntry(rs), merchantId, currency);
    }

    public List<SettlementItemView> items(String batchId) {
        return jdbc.query(
                "SELECT i.item_id, i.entry_id, i.item_type, i.amount_minor, e.net_minor, e.rule_version "
                        + "FROM settlement_item i JOIN clearing_entry e ON e.entry_id = i.entry_id "
                        + "WHERE i.batch_id = ? ORDER BY i.created_at, i.item_id",
                (rs, i) -> new SettlementItemView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getString(6)),
                batchId);
    }

    public PayoutRow findPayout(String payoutId) {
        List<PayoutRow> rows = jdbc.query(
                "SELECT payout_id, batch_id, merchant_id, amount_minor, currency, status, "
                        + "provider_idempotency_key, bank_reference, journal_id, version FROM payout_order "
                        + "WHERE payout_id = ?",
                (rs, i) -> mapPayout(rs), payoutId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PayoutRow findPayoutByBatch(String batchId) {
        List<PayoutRow> rows = jdbc.query(
                "SELECT payout_id, batch_id, merchant_id, amount_minor, currency, status, "
                        + "provider_idempotency_key, bank_reference, journal_id, version FROM payout_order "
                        + "WHERE batch_id = ?",
                (rs, i) -> mapPayout(rs), batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PayoutContext loadPayoutContext(String payoutId) {
        List<PayoutContext> rows = jdbc.query(
                "SELECT payout_id, provider_idempotency_key, merchant_id, amount_minor, currency "
                        + "FROM payout_order WHERE payout_id = ?",
                (rs, i) -> new PayoutContext(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5)),
                payoutId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static PayoutView payoutView(PayoutRow row) {
        return new PayoutView(row.payoutId(), row.batchId(), row.merchantId(), row.amountMinor(),
                row.currency(), row.status(), row.bankReference(), row.journalId(), row.version());
    }

    private static EntryRow mapEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EntryRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9),
                rs.getLong(10), rs.getString(11), rs.getString(12));
    }

    private static PayoutRow mapPayout(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PayoutRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getInt(10));
    }
}
