package com.example.paymentcore.ledger;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.common.DuplicateLedgerOperationException;
import com.example.paymentcore.common.InsufficientFundsException;
import com.example.paymentcore.domain.LedgerSide;
import com.example.paymentcore.domain.OutboxStatus;
import com.example.paymentcore.domain.OwnerScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 凭证提交事务：校验币种、归属与逐币种平衡，按正常方向计算余额变化，
 * 以带余额条件的原子更新防止透支，并与业务操作唯一键、分录和 outbox 同事务提交。
 */
@Service
public class LedgerTransactions {

    private final JdbcTemplate jdbc;
    private final LedgerAccountService accounts;
    private final ObjectMapper objectMapper;

    public LedgerTransactions(JdbcTemplate jdbc, LedgerAccountService accounts, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JournalResult post(JournalRequest request, String operationKey, String payloadDigest) {
        if (request.entries() == null || request.entries().isEmpty()) {
            throw new IllegalArgumentException("凭证必须包含至少一条分录");
        }
        requireOpenBook(request.bookId());

        List<LedgerAccountService.AccountRecord> resolved = new ArrayList<>();
        Map<String, long[]> perCurrency = new HashMap<>(); // [借方合计, 贷方合计]
        for (JournalEntryRequest entry : request.entries()) {
            if (entry.amountMinor() <= 0) {
                throw new IllegalArgumentException("分录金额必须为正整数");
            }
            LedgerAccountService.AccountRecord account = accounts.account(entry.accountId());
            if (account == null) {
                throw new ApiConflictException("unknown_account", "未知账户: " + entry.accountId());
            }
            if (!account.bookId().equals(request.bookId())) {
                throw new ApiConflictException("account_book_mismatch", "账户不属于该账套");
            }
            if (OwnerScope.MERCHANT.name().equals(account.ownerScope())
                    && !Objects.equals(account.merchantId(), request.merchantId())) {
                throw new ApiForbiddenException("商户不能使用其他商户的账户");
            }
            LedgerSide side;
            try {
                side = LedgerSide.valueOf(entry.side());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("分录方向必须为 DEBIT 或 CREDIT");
            }
            requireCurrency(account.currency());
            long[] sums = perCurrency.computeIfAbsent(account.currency(), key -> new long[2]);
            if (side == LedgerSide.DEBIT) {
                sums[0] += entry.amountMinor();
            } else {
                sums[1] += entry.amountMinor();
            }
            resolved.add(account);
        }

        // L01：按币种分别平衡，禁止用数值相等掩盖混币错误
        for (Map.Entry<String, long[]> sums : perCurrency.entrySet()) {
            if (sums.getValue()[0] != sums.getValue()[1]) {
                throw new ApiConflictException("journal_unbalanced",
                        "凭证在币种 " + sums.getKey() + " 内借贷不平衡");
            }
        }

        boolean correction = "CORRECTION".equals(request.kind());
        if (correction) {
            if (request.correctionOfJournalId() == null || request.correctionOfJournalId().isBlank()
                    || request.correctionReason() == null || request.correctionReason().isBlank()
                    || request.approvedBy() == null || request.approvedBy().isBlank()) {
                throw new IllegalArgumentException("修正凭证必须引用原凭证并携带原因与审批人");
            }
            Integer originals = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_journal WHERE journal_id = ?",
                    Integer.class, request.correctionOfJournalId());
            if (originals == null || originals == 0) {
                throw new ApiConflictException("unknown_correction_target", "原凭证不存在");
            }
        }

        String journalId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO ledger_business_operation(operation_key, book_id, merchant_id, "
                            + "operation_type, business_id, payload_digest, journal_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    operationKey, request.bookId(), request.merchantId(), request.operationType(),
                    request.businessId(), payloadDigest, journalId);
        } catch (DuplicateKeyException e) {
            throw new DuplicateLedgerOperationException("业务操作已存在");
        }

        Timestamp effectiveAt = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO ledger_journal(journal_id, book_id, operation_key, kind, effective_at, "
                        + "policy_version) VALUES (?, ?, ?, ?, ?, ?)",
                journalId, request.bookId(), operationKey, request.kind(), effectiveAt,
                request.policyVersion() == null ? LedgerConstants.POLICY_VERSION : request.policyVersion());

        for (JournalEntryRequest entry : request.entries()) {
            LedgerAccountService.AccountRecord account = accounts.account(entry.accountId());
            jdbc.update("INSERT INTO ledger_entry(journal_id, account_id, side, amount_minor, currency) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    journalId, entry.accountId(), entry.side(), entry.amountMinor(), account.currency());
        }

        if (correction) {
            jdbc.update("INSERT INTO correction_link(correction_journal_id, original_journal_id, reason, "
                            + "approved_by) VALUES (?, ?, ?, ?)",
                    journalId, request.correctionOfJournalId(), request.correctionReason(), request.approvedBy());
        }

        applyBalances(resolved, request.entries());
        enqueueJournal(journalId, request);
        return new JournalResult(journalId, true);
    }

    private void applyBalances(List<LedgerAccountService.AccountRecord> resolved,
                               List<JournalEntryRequest> entries) {
        Map<String, Long> deltas = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            JournalEntryRequest entry = entries.get(i);
            LedgerAccountService.AccountRecord account = resolved.get(i);
            long direction = LedgerSide.valueOf(account.normalSide()) == LedgerSide.valueOf(entry.side())
                    ? 1L : -1L;
            deltas.merge(account.accountId(), direction * entry.amountMinor(), Long::sum);
        }

        for (String accountId : new TreeSet<>(deltas.keySet())) {
            long delta = deltas.get(accountId);
            if (delta == 0) {
                continue;
            }
            LedgerAccountService.AccountRecord account = accounts.account(accountId);
            if (delta < 0 && !account.allowOverdraft()) {
                // L06：余额检查与资金变化在同一原子条件更新中完成
                int updated = jdbc.update("UPDATE account_balance SET posted_balance = posted_balance + ?, "
                                + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                                + "WHERE account_id = ? AND posted_balance + ? >= 0",
                        delta, accountId, delta);
                if (updated == 0) {
                    throw new InsufficientFundsException("账户可用余额不足: " + accountId);
                }
            } else {
                jdbc.update("UPDATE account_balance SET posted_balance = posted_balance + ?, "
                                + "version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE account_id = ?",
                        delta, accountId);
            }
        }
    }

    private void requireOpenBook(String bookId) {
        List<String> statuses = jdbc.queryForList("SELECT status FROM ledger_book WHERE book_id = ?",
                String.class, bookId);
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("未知账套: " + bookId);
        }
        if (!"OPEN".equals(statuses.get(0))) {
            throw new ApiConflictException("book_not_open", "账套未开放记账");
        }
    }

    private void requireCurrency(String currency) {
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM currency_metadata WHERE currency = ?",
                Integer.class, currency);
        if (rows == 0) {
            throw new ApiConflictException("unsupported_currency", "未登记的币种: " + currency);
        }
    }

    private void enqueueJournal(String journalId, JournalRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("journalId", journalId);
        payload.put("bookId", request.bookId());
        payload.put("operationType", request.operationType());
        payload.put("businessId", request.businessId());
        payload.put("kind", request.kind());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("账本事件序列化失败", e);
        }
        jdbc.update("INSERT INTO outbox_record(event_id, aggregate_id, aggregate_version, event_type, "
                        + "payload, status, publish_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), journalId, 0, "LEDGER_JOURNAL_POSTED", json,
                OutboxStatus.PENDING.name(), 0);
    }
}
