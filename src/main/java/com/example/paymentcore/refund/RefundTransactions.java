package com.example.paymentcore.refund;

import com.example.paymentcore.channel.ChannelResult;
import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.DuplicateRefundKeyException;
import com.example.paymentcore.common.EvidenceConflictException;
import com.example.paymentcore.domain.OutboxStatus;
import com.example.paymentcore.domain.PaymentStatus;
import com.example.paymentcore.domain.RefundStatus;
import com.example.paymentcore.domain.ReservationStatus;
import com.example.paymentcore.ledger.JournalEntryRequest;
import com.example.paymentcore.ledger.JournalRequest;
import com.example.paymentcore.ledger.LedgerAccountService;
import com.example.paymentcore.ledger.LedgerConstants;
import com.example.paymentcore.ledger.LedgerService;
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
 * 退款事务边界：
 * T6 受理 = 额度预留 + 退款身份 + 幂等绑定 + outbox，同事务且不调用渠道。
 * T7 结论 = 预留终结（确认或释放）+ 额度累计 + 退款投影 + 凭证 + outbox，同事务。
 */
@Service
public class RefundTransactions {

    private final JdbcTemplate jdbc;
    private final RefundRepository repository;
    private final LedgerService ledger;
    private final LedgerAccountService accounts;
    private final ObjectMapper objectMapper;

    public RefundTransactions(JdbcTemplate jdbc, RefundRepository repository, LedgerService ledger,
                              LedgerAccountService accounts, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.ledger = ledger;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RefundView create(String merchantId, String idempotencyKey, String payloadDigest,
                             String paymentId, long amountMinor, String currency, long refundableBase) {
        String refundId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO refund_idempotency(merchant_id, idempotency_key, payload_digest, "
                            + "refund_id) VALUES (?, ?, ?, ?)",
                    merchantId, idempotencyKey, payloadDigest, refundId);
        } catch (DuplicateKeyException e) {
            throw new DuplicateRefundKeyException("退款幂等键已被占用");
        }

        try {
            jdbc.update("INSERT INTO refund_pool(payment_id, book_id, merchant_id, currency, "
                            + "refundable_base, refunded_total, reserved_total, version) "
                            + "VALUES (?, ?, ?, ?, ?, 0, 0, 0)",
                    paymentId, LedgerConstants.BOOK_ID, merchantId, currency, refundableBase);
        } catch (DuplicateKeyException e) {
            // 额度池已由首次退款创建
        }

        // L03/L8：成功累计 + 处理中预留不得超过可退上限，由单条条件更新原子裁决
        int reserved = jdbc.update("UPDATE refund_pool SET reserved_total = reserved_total + ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE payment_id = ? AND refunded_total + reserved_total + ? <= refundable_base",
                amountMinor, paymentId, amountMinor);
        if (reserved == 0) {
            throw new ApiConflictException("refund_quota_exceeded", "退款超过可退额度");
        }

        String providerKey = "rf-" + UUID.randomUUID();
        jdbc.update("INSERT INTO refund_order(refund_id, payment_id, merchant_id, amount_minor, currency, "
                        + "status, provider_idempotency_key, version) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                refundId, paymentId, merchantId, amountMinor, currency, RefundStatus.ACCEPTED.name(),
                providerKey);

        String accountId = accounts.merchantPayable(LedgerConstants.BOOK_ID, merchantId, currency);
        jdbc.update("INSERT INTO refund_reservation(refund_id, payment_id, account_id, amount_minor, currency, "
                        + "status, version) VALUES (?, ?, ?, ?, ?, ?, 0)",
                refundId, paymentId, accountId, amountMinor, currency, ReservationStatus.RESERVED.name());
        recordReservationEvent(refundId, accountId, "RESERVED", amountMinor, null);
        jdbc.update("UPDATE account_balance SET reserved_total = reserved_total + ?, "
                + "version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE account_id = ?",
                amountMinor, accountId);

        appendEvent(refundId, RefundStatus.ACCEPTED.name(), "退款已受理并冻结额度，尚未调用渠道");
        enqueue(refundId, 0, "REFUND_ACCEPTED", paymentId, amountMinor, currency);
        return requireView(refundId);
    }

    @Transactional
    public boolean confirmSuccess(String refundId, ChannelResult evidence) {
        RefundRepository.RefundRow refund = requireRefund(refundId);
        if (evidence.providerReference() == null) {
            throw new EvidenceConflictException("退款成功证据缺少渠道引用");
        }
        if (evidence.amountMinor() != refund.amountMinor()
                || !refund.currency().equalsIgnoreCase(evidence.currency())) {
            throw new EvidenceConflictException("退款成功证据的金额或币种与退款单不一致");
        }

        int updated = jdbc.update("UPDATE refund_reservation SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE refund_id = ? AND status = ?",
                ReservationStatus.CONFIRMED.name(), refundId, ReservationStatus.RESERVED.name());
        if (updated == 0) {
            RefundRepository.ReservationRow reservation = repository.loadReservation(refundId);
            String current = reservation == null ? null : reservation.status();
            if (ReservationStatus.CONFIRMED.name().equals(current)) {
                return false; // 重复成功证据
            }
            throw new EvidenceConflictException("预留已终结为 " + current + "，不接受成功证据");
        }

        jdbc.update("UPDATE refund_pool SET reserved_total = reserved_total - ?, "
                        + "refunded_total = refunded_total + ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
                refund.amountMinor(), refund.amountMinor(), refund.paymentId());
        jdbc.update("UPDATE account_balance SET reserved_total = reserved_total - ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE account_id = ?",
                refund.amountMinor(), repository.loadReservation(refundId).accountId());
        recordReservationEvent(refundId, repository.loadReservation(refundId).accountId(), "CONFIRMED",
                refund.amountMinor(), evidence.providerReference());

        int refundUpdated = jdbc.update("UPDATE refund_order SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE refund_id = ? "
                        + "AND status IN (?, ?, ?)",
                RefundStatus.SUCCEEDED.name(), refundId, RefundStatus.ACCEPTED.name(),
                RefundStatus.PROCESSING.name(), RefundStatus.UNKNOWN.name());
        if (refundUpdated == 0) {
            throw new EvidenceConflictException("退款单状态不允许确认成功");
        }

        postRefundJournal(refund);
        appendEvent(refundId, RefundStatus.SUCCEEDED.name(), "可信渠道证据确认退款成功");
        int version = repository.findRefund(refundId).version();
        enqueue(refundId, version, "REFUND_SUCCEEDED", refund.paymentId(), refund.amountMinor(),
                refund.currency());
        return true;
    }

    /**
     * 明确失败或取消才释放预留；成功后收到的相反证据不得删除历史。
     * 冲突事件需要在抛出异常后仍然落库，因此对证据冲突不回滚。
     */
    @Transactional(noRollbackFor = EvidenceConflictException.class)
    public boolean release(String refundId, String detail) {
        RefundRepository.RefundRow refund = requireRefund(refundId);
        int updated = jdbc.update("UPDATE refund_reservation SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE refund_id = ? AND status = ?",
                ReservationStatus.RELEASED.name(), refundId, ReservationStatus.RESERVED.name());
        if (updated == 0) {
            RefundRepository.ReservationRow reservation = repository.loadReservation(refundId);
            String current = reservation == null ? null : reservation.status();
            if (ReservationStatus.RELEASED.name().equals(current)) {
                return false;
            }
            // L06：成功之后的失败/退回证据只追加，不删除原成功历史
            appendEvent(refundId, "EVIDENCE_CONFLICT", "终态后收到相反证据: " + detail);
            throw new EvidenceConflictException("预留已" + current + "，不能再释放");
        }

        String accountId = repository.loadReservation(refundId).accountId();
        jdbc.update("UPDATE refund_pool SET reserved_total = reserved_total - ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE payment_id = ?",
                refund.amountMinor(), refund.paymentId());
        jdbc.update("UPDATE account_balance SET reserved_total = reserved_total - ?, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE account_id = ?",
                refund.amountMinor(), accountId);
        recordReservationEvent(refundId, accountId, "RELEASED", refund.amountMinor(), null);

        jdbc.update("UPDATE refund_order SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE refund_id = ? AND status IN (?, ?, ?)",
                RefundStatus.FAILED.name(), refundId, RefundStatus.ACCEPTED.name(),
                RefundStatus.PROCESSING.name(), RefundStatus.UNKNOWN.name());
        appendEvent(refundId, RefundStatus.FAILED.name(), detail);
        int version = repository.findRefund(refundId).version();
        enqueue(refundId, version, "REFUND_FAILED", refund.paymentId(), refund.amountMinor(),
                refund.currency());
        return true;
    }

    @Transactional
    public boolean markUnknown(String refundId, String detail) {
        RefundRepository.RefundRow refund = requireRefund(refundId);
        int updated = jdbc.update("UPDATE refund_order SET status = ?, version = version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE refund_id = ? AND status IN (?, ?)",
                RefundStatus.UNKNOWN.name(), refundId, RefundStatus.ACCEPTED.name(),
                RefundStatus.PROCESSING.name());
        if (updated > 0) {
            // 预留保持冻结，等待原身份核查
            appendEvent(refundId, RefundStatus.UNKNOWN.name(), detail);
        }
        return updated > 0;
    }

    private void postRefundJournal(RefundRepository.RefundRow refund) {
        String payable = accounts.merchantPayable(LedgerConstants.BOOK_ID, refund.merchantId(),
                refund.currency());
        String receivable = accounts.receivableChannel(LedgerConstants.BOOK_ID, refund.currency());
        List<JournalEntryRequest> entries = new ArrayList<>();
        entries.add(new JournalEntryRequest(payable, "DEBIT", refund.amountMinor()));
        entries.add(new JournalEntryRequest(receivable, "CREDIT", refund.amountMinor()));
        ledger.postJournal(JournalRequest.of(LedgerConstants.BOOK_ID, refund.merchantId(),
                "REFUND_POSTING", refund.refundId(), "REFUND_CONFIRMED", entries));
    }

    private RefundRepository.RefundRow requireRefund(String refundId) {
        RefundRepository.RefundRow refund = repository.findRefund(refundId);
        if (refund == null) {
            throw new IllegalArgumentException("未知退款单: " + refundId);
        }
        return refund;
    }

    private RefundView requireView(String refundId) {
        RefundView view = repository.view(refundId);
        if (view == null) {
            throw new IllegalStateException("退款单创建后无法读取");
        }
        return view;
    }

    private void recordReservationEvent(String refundId, String accountId, String eventType, long amount,
                                        String evidenceId) {
        jdbc.update("INSERT INTO reservation_event(refund_id, account_id, event_type, amount_minor, "
                        + "evidence_id) VALUES (?, ?, ?, ?, ?)",
                refundId, accountId, eventType, amount, evidenceId);
    }

    private void appendEvent(String refundId, String status, String detail) {
        jdbc.update("INSERT INTO refund_event(refund_id, status, detail) VALUES (?, ?, ?)",
                refundId, status, detail);
    }

    private void enqueue(String refundId, int version, String eventType, String paymentId, long amountMinor,
                         String currency) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", refundId);
        payload.put("paymentId", paymentId);
        payload.put("amountMinor", amountMinor);
        payload.put("currency", currency);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("退款事件序列化失败", e);
        }
        jdbc.update("INSERT INTO outbox_record(event_id, aggregate_id, aggregate_version, event_type, "
                        + "payload, status, publish_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), refundId, version, eventType, json,
                OutboxStatus.PENDING.name(), 0);
    }
}
