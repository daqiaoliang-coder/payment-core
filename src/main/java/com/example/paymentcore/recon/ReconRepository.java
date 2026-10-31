package com.example.paymentcore.recon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class ReconRepository {

    public record ReportRow(String reportId, String provider, String reportType, String status,
                            int expectedPages, int receivedPages, int recordCount, long totalMinor,
                            String currency) {
    }

    public record StatementRow(String statementId, String provider, String bankReference, String direction,
                               long amountMinor, long allocatedMinor, String currency, String status) {
    }

    public record AllocationRow(String statementId, String entryId, long amountMinor) {
    }

    public record InternalPayment(String paymentId, long amountMinor, String currency) {
    }

    public record InternalRefund(String refundId, long amountMinor, String currency) {
    }

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReportRow findReportByFile(String provider, String reportType, Timestamp periodStart,
                                      Timestamp periodEnd, String fileDigest) {
        List<ReportRow> rows = jdbc.query(
                "SELECT report_id, provider, report_type, status, expected_pages, received_pages, record_count, "
                        + "total_minor, currency FROM recon_report WHERE provider = ? AND report_type = ? "
                        + "AND period_start = ? AND period_end = ? AND file_digest = ?",
                (rs, i) -> mapReport(rs), provider, reportType, periodStart, periodEnd, fileDigest);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReportRow findReport(String reportId) {
        List<ReportRow> rows = jdbc.query(
                "SELECT report_id, provider, report_type, status, expected_pages, received_pages, record_count, "
                        + "total_minor, currency FROM recon_report WHERE report_id = ?",
                (rs, i) -> mapReport(rs), reportId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DifferenceView> differences(String reportId) {
        return jdbc.query(
                "SELECT difference_id, report_id, record_key, difference_type, amount_minor, currency, status, "
                        + "detail FROM recon_difference WHERE report_id = ? ORDER BY created_at, difference_id",
                (rs, i) -> new DifferenceView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                reportId);
    }

    public int recordCount(String reportId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM recon_record WHERE report_id = ?",
                Integer.class, reportId);
        return count == null ? 0 : count;
    }

    public List<InternalPayment> succeededPayments() {
        return jdbc.query("SELECT payment_id, amount_minor, currency FROM payment_order WHERE status = 'SUCCEEDED'",
                (rs, i) -> new InternalPayment(rs.getString(1), rs.getLong(2), rs.getString(3)));
    }

    public List<InternalRefund> succeededRefunds() {
        return jdbc.query("SELECT refund_id, amount_minor, currency FROM refund_order WHERE status = 'SUCCEEDED'",
                (rs, i) -> new InternalRefund(rs.getString(1), rs.getLong(2), rs.getString(3)));
    }

    public StatementRow findStatement(String statementId) {
        List<StatementRow> rows = jdbc.query(
                "SELECT statement_id, provider, bank_reference, direction, amount_minor, allocated_minor, "
                        + "currency, status FROM bank_statement WHERE statement_id = ?",
                (rs, i) -> mapStatement(rs), statementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StatementRow findStatementByReference(String provider, String bankReference, String direction) {
        List<StatementRow> rows = jdbc.query(
                "SELECT statement_id, provider, bank_reference, direction, amount_minor, allocated_minor, "
                        + "currency, status FROM bank_statement "
                        + "WHERE provider = ? AND bank_reference = ? AND direction = ?",
                (rs, i) -> mapStatement(rs), provider, bankReference, direction);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AllocationRow findAllocation(String statementId, String entryId) {
        List<AllocationRow> rows = jdbc.query(
                "SELECT statement_id, entry_id, amount_minor FROM bank_allocation "
                        + "WHERE statement_id = ? AND entry_id = ?",
                (rs, i) -> new AllocationRow(rs.getString(1), rs.getString(2), rs.getLong(3)),
                statementId, entryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static ReportView view(ReportRow row) {
        return new ReportView(row.reportId(), row.provider(), row.reportType(), row.status(),
                row.expectedPages(), row.receivedPages(), row.recordCount(), row.totalMinor(), row.currency());
    }

    public static StatementView view(StatementRow row) {
        return new StatementView(row.statementId(), row.provider(), row.bankReference(), row.direction(),
                row.amountMinor(), row.allocatedMinor(), row.currency(), row.status());
    }

    private static ReportRow mapReport(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReportRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getLong(8), rs.getString(9));
    }

    private static StatementRow mapStatement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StatementRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getString(8));
    }
}
