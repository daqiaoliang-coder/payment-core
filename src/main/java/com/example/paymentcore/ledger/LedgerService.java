package com.example.paymentcore.ledger;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.DuplicateLedgerOperationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 记账用例：以“账套 + 商户 + 操作类别 + 业务动作”唯一键幂等提交。
 * 并发请求由数据库唯一约束裁决；冲突事务回滚后读取已提交凭证决定返回原结果还是内容冲突。
 */
@Service
public class LedgerService {

    private final JdbcTemplate jdbc;
    private final LedgerTransactions transactions;

    public LedgerService(JdbcTemplate jdbc, LedgerTransactions transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public JournalResult postJournal(JournalRequest request) {
        String operationKey = operationKey(request);
        String digest = digest(request);
        ExistingOperation existing = findExisting(operationKey);
        if (existing != null) {
            return resolveExisting(existing, digest);
        }
        try {
            return transactions.post(request, operationKey, digest);
        } catch (DuplicateLedgerOperationException e) {
            ExistingOperation winner = findExisting(operationKey);
            if (winner == null) {
                throw e;
            }
            return resolveExisting(winner, digest);
        }
    }

    /** L10：同一修正动作回调重复时只执行一次，原凭证与关联保留。 */
    public JournalResult postCorrection(String originalJournalId, String operationKeyBusinessId,
                                        String reason, String approvedBy, JournalRequest request) {
        JournalRequest correction = new JournalRequest(request.bookId(), request.merchantId(),
                "CORRECTION", operationKeyBusinessId, "CORRECTION",
                request.policyVersion(), request.entries(), originalJournalId, reason, approvedBy);
        return postJournal(correction);
    }

    public String operationKey(JournalRequest request) {
        return request.bookId() + "|" + (request.merchantId() == null ? "-" : request.merchantId())
                + "|" + request.operationType() + "|" + request.businessId();
    }

    private JournalResult resolveExisting(ExistingOperation existing, String digest) {
        if (!existing.payloadDigest().equals(digest)) {
            throw new ApiConflictException("ledger_operation_conflict",
                    "同一业务操作对应的分录内容不一致");
        }
        return new JournalResult(existing.journalId(), false);
    }

    private ExistingOperation findExisting(String operationKey) {
        List<ExistingOperation> rows = jdbc.query(
                "SELECT journal_id, payload_digest FROM ledger_business_operation WHERE operation_key = ?",
                (rs, i) -> new ExistingOperation(rs.getString(1), rs.getString(2)), operationKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String digest(JournalRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.bookId()).append('|').append(request.merchantId()).append('|')
                .append(request.operationType()).append('|').append(request.businessId()).append('|')
                .append(request.kind()).append('|').append(request.policyVersion()).append('|')
                .append(request.correctionOfJournalId()).append('|').append(request.correctionReason())
                .append('|').append(request.approvedBy());
        request.entries().stream()
                .map(entry -> entry.accountId() + ":" + entry.side() + ":" + entry.amountMinor())
                .sorted()
                .forEach(item -> canonical.append('|').append(item));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private record ExistingOperation(String journalId, String payloadDigest) {
    }
}
