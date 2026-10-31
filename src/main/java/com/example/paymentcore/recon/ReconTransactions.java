package com.example.paymentcore.recon;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiNotFoundException;
import com.example.paymentcore.domain.ReconDifferenceStatus;
import com.example.paymentcore.domain.ReconDifferenceType;
import com.example.paymentcore.domain.ReconRecordType;
import com.example.paymentcore.domain.ReconReportStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 对账导入事务边界：
 * 文件身份（范围 + 摘要）与业务明细标识分别去重，同文件重投不重复入账；
 * 完整性结论（IMPORTED/INCOMPLETE/EMPTY）独立于逐笔差异结论。
 */
@Service
public class ReconTransactions {

    private final JdbcTemplate jdbc;
    private final ReconRepository repository;

    public ReconTransactions(JdbcTemplate jdbc, ReconRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Transactional
    public ReportView importReport(ReconReportRequest request) {
        Timestamp periodStart = Timestamp.from(request.periodStart());
        Timestamp periodEnd = Timestamp.from(request.periodEnd());
        String currency = request.currency().toUpperCase(Locale.ROOT);
        ReconRepository.ReportRow existing = repository.findReportByFile(request.provider(), request.reportType(),
                periodStart, periodEnd, request.fileDigest());
        if (existing != null) {
            return ReconRepository.view(existing);
        }

        int expectedPages = Math.max(request.expectedPages(), 1);
        Set<Integer> receivedPageNumbers = new LinkedHashSet<>();
        for (ReconReportRequest.Page page : request.pages()) {
            if (page.pageNo() >= 1 && page.pageNo() <= expectedPages) {
                receivedPageNumbers.add(page.pageNo());
            }
        }
        int receivedPages = receivedPageNumbers.size();

        Set<String> recordKeys = new LinkedHashSet<>();
        long total = 0L;
        for (ReconReportRequest.Page page : request.pages()) {
            for (ReconReportRequest.Record record : page.records()) {
                recordKeys.add(record.recordKey());
                total += record.amountMinor();
            }
        }
        int recordCount = recordKeys.size();
        String status = receivedPages < expectedPages
                ? ReconReportStatus.INCOMPLETE.name()
                : (recordCount == 0 ? ReconReportStatus.EMPTY.name() : ReconReportStatus.IMPORTED.name());

        String reportId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO recon_report(report_id, provider, report_type, period_start, period_end, "
                            + "file_digest, expected_pages, received_pages, record_count, total_minor, currency, "
                            + "status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    reportId, request.provider(), request.reportType(), periodStart, periodEnd,
                    request.fileDigest(), expectedPages, receivedPages, recordCount, total, currency, status);
        } catch (DuplicateKeyException e) {
            ReconRepository.ReportRow winner = repository.findReportByFile(request.provider(),
                    request.reportType(), periodStart, periodEnd, request.fileDigest());
            if (winner == null) {
                throw e;
            }
            return ReconRepository.view(winner);
        }

        for (ReconReportRequest.Page page : request.pages()) {
            jdbc.update("MERGE INTO recon_page KEY(report_id, page_no) VALUES (?, ?, ?)",
                    reportId, page.pageNo(), page.pageDigest());
        }

        int insertedRecords = 0;
        for (ReconReportRequest.Page page : request.pages()) {
            for (ReconReportRequest.Record record : page.records()) {
                if (insertRecord(reportId, request.provider(), currency, record)) {
                    insertedRecords++;
                }
            }
        }

        // 只有新明细才需要再次逐笔核对；修订报告中的重复明细不重复入账
        if (insertedRecords > 0 && ReconReportStatus.IMPORTED.name().equals(status)) {
            reconcile(reportId, currency, request.pages());
        }
        return ReconRepository.view(repository.findReport(reportId));
    }

    private boolean insertRecord(String reportId, String provider, String currency,
                                 ReconReportRequest.Record record) {
        try {
            jdbc.update("INSERT INTO recon_record(record_id, report_id, provider, record_key, record_type, "
                            + "business_id, amount_minor, currency, record_digest) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), reportId, provider, record.recordKey(),
                    normalizeType(record.recordType()), record.businessId(), record.amountMinor(), currency,
                    digest(record));
            return true;
        } catch (DuplicateKeyException e) {
            // 业务明细标识已存在：同一经济动作不重复入账
            return false;
        }
    }

    private void reconcile(String reportId, String currency, List<ReconReportRequest.Page> pages) {
        Set<String> reportedPayments = new LinkedHashSet<>();
        Set<String> reportedRefunds = new LinkedHashSet<>();
        boolean hasPaymentRecords = false;
        boolean hasRefundRecords = false;

        for (ReconReportRequest.Page page : pages) {
            for (ReconReportRequest.Record record : page.records()) {
                String type = normalizeType(record.recordType());
                String businessId = businessId(record);
                if (ReconRecordType.PAYMENT.name().equals(type)) {
                    hasPaymentRecords = true;
                    reportedPayments.add(businessId);
                    ReconRepository.InternalPayment payment = findPayment(businessId);
                    if (payment == null) {
                        difference(reportId, businessId, ReconDifferenceType.EXTRA_IN_REPORT,
                                record.amountMinor(), currency, "渠道报告多出内部不存在的支付");
                    } else if (payment.amountMinor() != record.amountMinor()) {
                        difference(reportId, businessId, ReconDifferenceType.AMOUNT_MISMATCH,
                                record.amountMinor() - payment.amountMinor(), currency,
                                "支付金额不一致：报告 " + record.amountMinor() + "，内部 " + payment.amountMinor());
                    }
                } else if (ReconRecordType.REFUND.name().equals(type)) {
                    hasRefundRecords = true;
                    reportedRefunds.add(businessId);
                    ReconRepository.InternalRefund refund = findRefund(businessId);
                    if (refund == null) {
                        difference(reportId, businessId, ReconDifferenceType.EXTRA_IN_REPORT,
                                record.amountMinor(), currency, "渠道报告多出内部不存在的退款");
                    } else if (refund.amountMinor() != record.amountMinor()) {
                        difference(reportId, businessId, ReconDifferenceType.AMOUNT_MISMATCH,
                                record.amountMinor() - refund.amountMinor(), currency,
                                "退款金额不一致：报告 " + record.amountMinor() + "，内部 " + refund.amountMinor());
                    }
                }
            }
        }

        // S08：汇总相等仍要逐笔核对；只在报告声明了该记录类型时才比对内部缺失
        if (hasPaymentRecords) {
            for (ReconRepository.InternalPayment payment : repository.succeededPayments()) {
                if (!reportedPayments.contains(payment.paymentId())) {
                    difference(reportId, payment.paymentId(), ReconDifferenceType.MISSING_IN_REPORT,
                            payment.amountMinor(), payment.currency(), "内部成功支付未出现在渠道报告");
                }
            }
        }
        if (hasRefundRecords) {
            for (ReconRepository.InternalRefund refund : repository.succeededRefunds()) {
                if (!reportedRefunds.contains(refund.refundId())) {
                    difference(reportId, refund.refundId(), ReconDifferenceType.MISSING_IN_REPORT,
                            refund.amountMinor(), refund.currency(), "内部成功退款未出现在渠道报告");
                }
            }
        }
    }

    private void difference(String reportId, String recordKey, ReconDifferenceType type, long amountMinor,
                            String currency, String detail) {
        try {
            jdbc.update("INSERT INTO recon_difference(difference_id, report_id, record_key, difference_type, "
                            + "amount_minor, currency, status, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), reportId, recordKey, type.name(), amountMinor, currency,
                    ReconDifferenceStatus.OPEN.name(), detail);
        } catch (DuplicateKeyException e) {
            // 同一报告版本的同一逐笔差异只记一次
        }
    }

    @Transactional
    public StatementView importStatement(String provider, String bankReference, String direction,
                                         long amountMinor, String currency) {
        String normalizedDirection = direction.toUpperCase(Locale.ROOT);
        ReconRepository.StatementRow existing = repository.findStatementByReference(provider, bankReference,
                normalizedDirection);
        if (existing != null) {
            return ReconRepository.view(existing);
        }
        String statementId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO bank_statement(statement_id, provider, bank_reference, direction, "
                            + "amount_minor, allocated_minor, currency, status) VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                    statementId, provider, bankReference, normalizedDirection, amountMinor,
                    currency.toUpperCase(Locale.ROOT), "OPEN");
        } catch (DuplicateKeyException e) {
            ReconRepository.StatementRow winner = repository.findStatementByReference(provider, bankReference,
                    normalizedDirection);
            if (winner == null) {
                throw e;
            }
            return ReconRepository.view(winner);
        }
        return ReconRepository.view(repository.findStatement(statementId));
    }

    @Transactional
    public StatementView allocate(String statementId, String entryId, long amountMinor) {
        ReconRepository.StatementRow statement = repository.findStatement(statementId);
        if (statement == null) {
            throw new ApiNotFoundException("银行流水不存在: " + statementId);
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("分配金额必须为正整数");
        }
        Integer entryExists = jdbc.queryForObject("SELECT COUNT(*) FROM clearing_entry WHERE entry_id = ?",
                Integer.class, entryId);
        if (entryExists == null || entryExists == 0) {
            throw new ApiConflictException("unknown_clearing_entry", "清分明细不存在: " + entryId);
        }
        if (repository.findAllocation(statementId, entryId) != null) {
            throw new ApiConflictException("duplicate_bank_allocation", "同一银行流水不能重复分配给同一明细");
        }

        // S09：分配与剩余额检查在同一条条件更新中完成
        int allocated = jdbc.update("UPDATE bank_statement SET allocated_minor = allocated_minor + ? "
                        + "WHERE statement_id = ? AND allocated_minor + ? <= amount_minor",
                amountMinor, statementId, amountMinor);
        if (allocated == 0) {
            throw new ApiConflictException("bank_statement_exhausted", "银行流水剩余可分配金额不足");
        }
        try {
            jdbc.update("INSERT INTO bank_allocation(allocation_id, statement_id, entry_id, amount_minor) "
                            + "VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(), statementId, entryId, amountMinor);
        } catch (DuplicateKeyException e) {
            throw new ApiConflictException("duplicate_bank_allocation", "同一银行流水不能重复分配给同一明细");
        }

        String status = statement.allocatedMinor() + amountMinor >= statement.amountMinor()
                ? "ALLOCATED" : "PARTIAL";
        jdbc.update("UPDATE bank_statement SET status = ? WHERE statement_id = ?", status, statementId);
        return ReconRepository.view(repository.findStatement(statementId));
    }

    private ReconRepository.InternalPayment findPayment(String paymentId) {
        List<ReconRepository.InternalPayment> rows = jdbc.query(
                "SELECT payment_id, amount_minor, currency FROM payment_order WHERE payment_id = ?",
                (rs, i) -> new ReconRepository.InternalPayment(rs.getString(1), rs.getLong(2), rs.getString(3)),
                paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ReconRepository.InternalRefund findRefund(String refundId) {
        List<ReconRepository.InternalRefund> rows = jdbc.query(
                "SELECT refund_id, amount_minor, currency FROM refund_order WHERE refund_id = ?",
                (rs, i) -> new ReconRepository.InternalRefund(rs.getString(1), rs.getLong(2), rs.getString(3)),
                refundId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String businessId(ReconReportRequest.Record record) {
        return record.businessId() == null || record.businessId().isBlank()
                ? record.recordKey() : record.businessId();
    }

    private String normalizeType(String recordType) {
        return recordType.toUpperCase(Locale.ROOT);
    }

    private String digest(ReconReportRequest.Record record) {
        String canonical = record.recordKey() + "|" + normalizeType(record.recordType()) + "|"
                + record.businessId() + "|" + record.amountMinor();
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
