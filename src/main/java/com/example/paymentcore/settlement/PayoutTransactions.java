package com.example.paymentcore.settlement;

import com.example.paymentcore.bank.MockBank;
import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiNotFoundException;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.common.InsufficientSettlementFundsException;
import com.example.paymentcore.domain.OutboxStatus;
import com.example.paymentcore.domain.PayoutStatus;
import com.example.paymentcore.domain.SettlementBatchStatus;
import com.example.paymentcore.ledger.JournalEntryRequest;
import com.example.paymentcore.ledger.JournalRequest;
import com.example.paymentcore.ledger.JournalResult;
import com.example.paymentcore.ledger.LedgerAccountService;
import com.example.paymentcore.ledger.LedgerConstants;
import com.example.paymentcore.ledger.LedgerService;
import com.example.paymentcore.security.OperationAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 出款事务边界：
 * 创建 = 原子预留应付款 + 持久化外部意图身份，同事务且不调用银行；
 * 成功 = 凭证 + 释放预留 + 单据与批次状态 + outbox，同事务；
 * UNKNOWN 保留占额，退回追加关联原出款的修正凭证。
 */
@Service
public class PayoutTransactions {

    private final JdbcTemplate jdbc;
    private final SettlementRepository repository;
    private final LedgerService ledger;
    private final LedgerAccountService accounts;
    private final ObjectMapper objectMapper;
    private final OperationAuditService audit;

    public PayoutTransactions(JdbcTemplate jdbc, SettlementRepository repository, LedgerService ledger,
                              LedgerAccountService accounts, ObjectMapper objectMapper,
                              OperationAuditService audit) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.ledger = ledger;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Transactional
    public PayoutView create(String batchId) {
        SettlementRepository.BatchRow batch = repository.findBatch(batchId);
        if (batch == null) {
            throw new ApiNotFoundException("结算批次不存在: " + batchId);
        }
        SettlementRepository.PayoutRow existing = repository.findPayoutByBatch(batchId);
        if (existing != null) {
            return SettlementRepository.payoutView(existing);
        }
        if (!SettlementBatchStatus.FROZEN.name().equals(batch.status())) {
            throw new ApiConflictException("batch_not_frozen", "只有已冻结批次才能创建出款单");
        }
        if (batch.netMinor() <= 0) {
            throw new ApiConflictException("settlement_net_not_payable",
                    "批次净额不为正，不能形成正向付款");
        }

        long amount = batch.netMinor();
        String accountId = accounts.merchantPayable(LedgerConstants.BOOK_ID, batch.merchantId(),
                batch.currency());
        // S02：检查可用余额与建立预留必须原子完成，拒绝重复消费同一份应付权利
        int reserved = jdbc.update("UPDATE account_balance SET reserved_total = reserved_total + ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE account_id = ? AND posted_balance - reserved_total >= ?",
                amount, accountId, amount);
        if (reserved == 0) {
            throw new InsufficientSettlementFundsException("商户应付可用余额不足，不能出款: " + accountId);
        }

        String payoutId = UUID.randomUUID().toString();
        String providerKey = "pt-" + UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO payout_order(payout_id, batch_id, merchant_id, currency, amount_minor, "
                            + "status, provider_idempotency_key, version) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                    payoutId, batchId, batch.merchantId(), batch.currency(), amount,
                    PayoutStatus.CREATED.name(), providerKey);
        } catch (DuplicateKeyException e) {
            // 并发创建：本事务回滚（含预留），由上层读取已提交出款单
            throw new ApiConflictException("payout_already_exists", "该批次已创建出款单");
        }
        appendEvent(payoutId, PayoutStatus.CREATED.name(), "已原子预留应付并持久化外部付款意图");
        enqueue(payoutId, 0, "PAYOUT_CREATED", batch.merchantId(), amount, batch.currency());
        audit.recordAccepted("settlement-service", "PAYOUT_CREATE", "PAYOUT", payoutId, batch.ruleVersion(),
                batchId, "payout_order", null, "batchId=" + batchId + "; amount=" + amount + " "
                        + batch.currency());
        return SettlementRepository.payoutView(repository.findPayout(payoutId));
    }

    @Transactional
    public boolean confirmSuccess(String payoutId, MockBank.BankResult evidence) {
        SettlementRepository.PayoutRow payout = requirePayout(payoutId);
        if (evidence.bankReference() == null) {
            throw new EvidenceConflictException("出款成功证据缺少银行引用");
        }
        int updated = jdbc.update("UPDATE payout_order SET status = ?, bank_reference = ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE payout_id = ? "
                        + "AND status IN (?, ?, ?)",
                PayoutStatus.SUCCEEDED.name(), evidence.bankReference(), payoutId,
                PayoutStatus.CREATED.name(), PayoutStatus.PROCESSING.name(), PayoutStatus.UNKNOWN.name());
        if (updated == 0) {
            SettlementRepository.PayoutRow current = repository.findPayout(payoutId);
            if (current != null && PayoutStatus.SUCCEEDED.name().equals(current.status())) {
                return false; // 重复成功证据
            }
            throw new EvidenceConflictException("出款单状态不允许确认成功");
        }

        String payable = accounts.merchantPayable(LedgerConstants.BOOK_ID, payout.merchantId(),
                payout.currency());
        String bank = accounts.bank(LedgerConstants.BOOK_ID, payout.currency());
        List<JournalEntryRequest> entries = new ArrayList<>();
        entries.add(new JournalEntryRequest(payable, "DEBIT", payout.amountMinor()));
        entries.add(new JournalEntryRequest(bank, "CREDIT", payout.amountMinor()));
        JournalResult journal = ledger.postJournal(JournalRequest.of(LedgerConstants.BOOK_ID,
                payout.merchantId(), "SETTLEMENT_PAYOUT", payoutId, "PAYOUT_CONFIRMED", entries));
        jdbc.update("UPDATE payout_order SET journal_id = ?, updated_at = CURRENT_TIMESTAMP WHERE payout_id = ?",
                journal.journalId(), payoutId);

        int released = jdbc.update("UPDATE account_balance SET reserved_total = reserved_total - ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE account_id = ? AND reserved_total >= ?",
                payout.amountMinor(), payable, payout.amountMinor());
        if (released == 0) {
            throw new IllegalStateException("出款预留与账户预留不一致: " + payoutId);
        }

        jdbc.update("UPDATE settlement_batch SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE batch_id = ? AND status = ?",
                SettlementBatchStatus.PAID.name(), payout.batchId(), SettlementBatchStatus.FROZEN.name());
        appendEvent(payoutId, PayoutStatus.SUCCEEDED.name(), "银行证据确认出款成功");
        enqueue(payoutId, repository.findPayout(payoutId).version(), "PAYOUT_SUCCEEDED", payout.merchantId(),
                payout.amountMinor(), payout.currency());
        return true;
    }

    /** 明确拒绝才释放预留；UNKNOWN 不因超时或到期释放。 */
    @Transactional
    public boolean release(String payoutId, String detail) {
        SettlementRepository.PayoutRow payout = requirePayout(payoutId);
        int updated = jdbc.update("UPDATE payout_order SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE payout_id = ? AND status IN (?, ?, ?)",
                PayoutStatus.FAILED.name(), payoutId, PayoutStatus.CREATED.name(),
                PayoutStatus.PROCESSING.name(), PayoutStatus.UNKNOWN.name());
        if (updated == 0) {
            return false;
        }
        String payable = accounts.merchantPayable(LedgerConstants.BOOK_ID, payout.merchantId(),
                payout.currency());
        jdbc.update("UPDATE account_balance SET reserved_total = reserved_total - ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE account_id = ? AND reserved_total >= ?",
                payout.amountMinor(), payable, payout.amountMinor());
        appendEvent(payoutId, PayoutStatus.FAILED.name(), detail);
        enqueue(payoutId, repository.findPayout(payoutId).version(), "PAYOUT_FAILED", payout.merchantId(),
                payout.amountMinor(), payout.currency());
        return true;
    }

    @Transactional
    public boolean markUnknown(String payoutId, String detail) {
        int updated = jdbc.update("UPDATE payout_order SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE payout_id = ? AND status = ?",
                PayoutStatus.UNKNOWN.name(), payoutId, PayoutStatus.PROCESSING.name());
        if (updated > 0) {
            // 预留保持冻结，等待按原银行身份核查
            appendEvent(payoutId, PayoutStatus.UNKNOWN.name(), detail);
            enqueue(payoutId, repository.findPayout(payoutId).version(), "PAYOUT_UNKNOWN",
                    repository.findPayout(payoutId).merchantId(),
                    repository.findPayout(payoutId).amountMinor(),
                    repository.findPayout(payoutId).currency());
        }
        return updated > 0;
    }

    /** S10：成功后资金退回，追加退回事实与修正凭证，不把旧成功改成失败。 */
    @Transactional
    public PayoutView markReturned(String payoutId, String reason, String approvedBy) {
        SettlementRepository.PayoutRow payout = requirePayout(payoutId);
        if (PayoutStatus.RETURNED.name().equals(payout.status())) {
            return SettlementRepository.payoutView(payout);
        }
        if (!PayoutStatus.SUCCEEDED.name().equals(payout.status()) || payout.journalId() == null) {
            throw new ApiConflictException("payout_not_returnable", "只有已成功的出款才能登记退回");
        }
        String payable = accounts.merchantPayable(LedgerConstants.BOOK_ID, payout.merchantId(),
                payout.currency());
        String bank = accounts.bank(LedgerConstants.BOOK_ID, payout.currency());
        List<JournalEntryRequest> entries = new ArrayList<>();
        entries.add(new JournalEntryRequest(bank, "DEBIT", payout.amountMinor()));
        entries.add(new JournalEntryRequest(payable, "CREDIT", payout.amountMinor()));
        ledger.postCorrection(payout.journalId(), "payout-return:" + payoutId, reason, approvedBy,
                JournalRequest.of(LedgerConstants.BOOK_ID, payout.merchantId(), "SETTLEMENT_PAYOUT_RETURN",
                        payoutId, "PAYOUT_RETURN", entries));

        jdbc.update("UPDATE payout_order SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE payout_id = ? AND status = ?",
                PayoutStatus.RETURNED.name(), payoutId, PayoutStatus.SUCCEEDED.name());
        jdbc.update("UPDATE settlement_batch SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE batch_id = ? AND status = ?",
                SettlementBatchStatus.RETURNED.name(), payout.batchId(), SettlementBatchStatus.PAID.name());
        appendEvent(payoutId, PayoutStatus.RETURNED.name(), "银行退回: " + reason);
        enqueue(payoutId, repository.findPayout(payoutId).version(), "PAYOUT_RETURNED", payout.merchantId(),
                payout.amountMinor(), payout.currency());
        audit.recordAccepted(approvedBy, "PAYOUT_RETURN", "PAYOUT", payoutId, null, payout.bankReference(),
                "payout_order", null, "reason=" + reason);
        return SettlementRepository.payoutView(repository.findPayout(payoutId));
    }

    private SettlementRepository.PayoutRow requirePayout(String payoutId) {
        SettlementRepository.PayoutRow payout = repository.findPayout(payoutId);
        if (payout == null) {
            throw new ApiNotFoundException("出款单不存在: " + payoutId);
        }
        return payout;
    }

    private void appendEvent(String payoutId, String status, String detail) {
        jdbc.update("INSERT INTO payout_event(payout_id, status, detail) VALUES (?, ?, ?)",
                payoutId, status, detail);
    }

    private void enqueue(String payoutId, int version, String eventType, String merchantId, long amountMinor,
                         String currency) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payoutId", payoutId);
        payload.put("merchantId", merchantId);
        payload.put("amountMinor", amountMinor);
        payload.put("currency", currency);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("出款事件序列化失败", e);
        }
        jdbc.update("INSERT INTO outbox_record(event_id, aggregate_id, aggregate_version, event_type, "
                        + "payload, status, publish_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), payoutId, version, eventType, json,
                OutboxStatus.PENDING.name(), 0);
    }
}
