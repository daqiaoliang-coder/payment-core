package com.example.paymentcore.settlement;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.common.ApiNotFoundException;
import com.example.paymentcore.domain.OutboxStatus;
import com.example.paymentcore.domain.PaymentStatus;
import com.example.paymentcore.domain.RefundStatus;
import com.example.paymentcore.domain.SettlementBatchStatus;
import com.example.paymentcore.domain.SettlementItemType;
import com.example.paymentcore.ledger.LedgerConstants;
import com.example.paymentcore.payment.PaymentRepository;
import com.example.paymentcore.refund.RefundRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 结算事务边界：
 * 批次创建/加入成员/冻结在本地事务内维护批次合计；
 * 同一清分明细以 item_key 唯一约束防止被两个有效批次重复消费（S01）。
 */
@Service
public class SettlementTransactions {

    private final JdbcTemplate jdbc;
    private final SettlementRepository repository;
    private final FeeRuleService feeRules;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final ObjectMapper objectMapper;

    public SettlementTransactions(JdbcTemplate jdbc, SettlementRepository repository, FeeRuleService feeRules,
                                  PaymentRepository payments, RefundRepository refunds, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.feeRules = feeRules;
        this.payments = payments;
        this.refunds = refunds;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BatchView openBatch(String merchantId, String currency, String ruleVersion) {
        FeeRuleService.FeeRule rule = feeRules.requireRule(ruleVersion);
        String batchId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO settlement_batch(batch_id, merchant_id, currency, rule_version, status, "
                        + "gross_minor, refund_minor, fee_minor, net_minor, item_count, version) "
                        + "VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0)",
                batchId, merchantId, currency, rule.ruleVersion(), SettlementBatchStatus.OPEN.name());
        return repository.batchView(batchId);
    }

    @Transactional
    public SettlementItemView addPayment(String batchId, String paymentId) {
        SettlementRepository.BatchRow batch = requireOpenBatch(batchId);
        PaymentRepository.PaymentRow payment = payments.loadPaymentRow(paymentId);
        if (payment == null) {
            throw new ApiNotFoundException("支付单不存在: " + paymentId);
        }
        if (!Objects.equals(payment.merchantId(), batch.merchantId())) {
            throw new ApiForbiddenException("不能把其他商户的支付单加入本批次");
        }
        if (!PaymentStatus.SUCCEEDED.name().equals(payment.status())) {
            throw new ApiConflictException("settlement_source_not_settled", "只有成功支付才能进入清分");
        }
        if (!batch.currency().equals(payment.currency())) {
            throw new ApiConflictException("settlement_currency_mismatch", "支付单币种与批次不一致");
        }
        String itemKey = "P:" + paymentId;
        if (repository.findEntryByItemKey(itemKey) != null) {
            throw new ApiConflictException("settlement_item_already_consumed", "该支付已被有效批次消费");
        }

        FeeRuleService.FeeRule rule = feeRules.requireRule(batch.ruleVersion());
        long gross = payment.amountMinor();
        long fee = rule.feeFor(gross);
        long net = gross - fee;
        String entryId = insertEntry(itemKey, SettlementItemType.PAYMENT, paymentId, null, batch, gross, fee, net);
        insertItem(batchId, entryId, SettlementItemType.PAYMENT, gross);
        updateTotals(batchId, gross, 0L, fee, net);
        return new SettlementItemView(entryId, entryId, SettlementItemType.PAYMENT.name(), gross, net,
                rule.ruleVersion());
    }

    @Transactional
    public SettlementItemView addRefund(String batchId, String refundId) {
        SettlementRepository.BatchRow batch = requireOpenBatch(batchId);
        RefundRepository.RefundRow refund = refunds.findRefund(refundId);
        if (refund == null) {
            throw new ApiNotFoundException("退款单不存在: " + refundId);
        }
        if (!Objects.equals(refund.merchantId(), batch.merchantId())) {
            throw new ApiForbiddenException("不能把其他商户的退款单加入本批次");
        }
        if (!RefundStatus.SUCCEEDED.name().equals(refund.status())) {
            throw new ApiConflictException("settlement_source_not_settled", "只有成功退款才能进入清分");
        }
        if (!batch.currency().equals(refund.currency())) {
            throw new ApiConflictException("settlement_currency_mismatch", "退款单币种与批次不一致");
        }
        String itemKey = "R:" + refundId;
        if (repository.findEntryByItemKey(itemKey) != null) {
            throw new ApiConflictException("settlement_item_already_consumed", "该退款已被有效批次消费");
        }

        long amount = refund.amountMinor();
        String entryId = insertEntry(itemKey, SettlementItemType.REFUND, null, refundId, batch, -amount, 0L,
                -amount);
        insertItem(batchId, entryId, SettlementItemType.REFUND, -amount);
        updateTotals(batchId, 0L, amount, 0L, -amount);
        return new SettlementItemView(entryId, entryId, SettlementItemType.REFUND.name(), -amount, -amount,
                batch.ruleVersion());
    }

    @Transactional
    public BatchView freeze(String batchId) {
        int updated = jdbc.update("UPDATE settlement_batch SET status = ?, frozen_at = CURRENT_TIMESTAMP, "
                        + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE batch_id = ? AND status = ? AND item_count > 0",
                SettlementBatchStatus.FROZEN.name(), batchId, SettlementBatchStatus.OPEN.name());
        SettlementRepository.BatchRow batch = repository.findBatch(batchId);
        if (updated == 0) {
            if (batch == null) {
                throw new ApiNotFoundException("结算批次不存在: " + batchId);
            }
            if (SettlementBatchStatus.FROZEN.name().equals(batch.status())) {
                return repository.batchView(batchId);
            }
            throw new ApiConflictException("batch_not_freezable", "批次为空或状态不允许冻结");
        }
        enqueue(batchId, batch.version(), "BATCH_FROZEN", batch.merchantId());
        return repository.batchView(batchId);
    }

    private SettlementRepository.BatchRow requireOpenBatch(String batchId) {
        SettlementRepository.BatchRow batch = repository.findBatch(batchId);
        if (batch == null) {
            throw new ApiNotFoundException("结算批次不存在: " + batchId);
        }
        if (!SettlementBatchStatus.OPEN.name().equals(batch.status())) {
            throw new ApiConflictException("batch_not_open", "批次已冻结，成员不可再变更");
        }
        return batch;
    }

    private String insertEntry(String itemKey, SettlementItemType type, String paymentId, String refundId,
                               SettlementRepository.BatchRow batch, long gross, long fee, long net) {
        String entryId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO clearing_entry(entry_id, item_key, item_type, payment_id, refund_id, "
                            + "merchant_id, currency, gross_minor, fee_minor, net_minor, rule_version, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    entryId, itemKey, type.name(), paymentId, refundId, batch.merchantId(), batch.currency(),
                    gross, fee, net, batch.ruleVersion(), "CLEARED");
        } catch (DuplicateKeyException e) {
            throw new ApiConflictException("settlement_item_already_consumed", "该应结项目已被有效批次消费");
        }
        return entryId;
    }

    private void insertItem(String batchId, String entryId, SettlementItemType type, long amountMinor) {
        try {
            jdbc.update("INSERT INTO settlement_item(item_id, batch_id, entry_id, item_type, amount_minor) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), batchId, entryId, type.name(), amountMinor);
        } catch (DuplicateKeyException e) {
            throw new ApiConflictException("settlement_item_already_consumed", "该应结项目已被其他批次占用");
        }
    }

    private void updateTotals(String batchId, long gross, long refund, long fee, long net) {
        int updated = jdbc.update("UPDATE settlement_batch SET gross_minor = gross_minor + ?, "
                        + "refund_minor = refund_minor + ?, fee_minor = fee_minor + ?, net_minor = net_minor + ?, "
                        + "item_count = item_count + 1, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE batch_id = ? AND status = ?",
                gross, refund, fee, net, batchId, SettlementBatchStatus.OPEN.name());
        if (updated == 0) {
            throw new ApiConflictException("batch_not_open", "批次状态已变化，成员加入失败");
        }
    }

    private void enqueue(String aggregateId, int version, String eventType, String merchantId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", aggregateId);
        payload.put("merchantId", merchantId);
        payload.put("status", SettlementBatchStatus.FROZEN.name());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("结算事件序列化失败", e);
        }
        jdbc.update("INSERT INTO outbox_record(event_id, aggregate_id, aggregate_version, event_type, "
                        + "payload, status, publish_version) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), aggregateId, version, eventType, json,
                OutboxStatus.PENDING.name(), 0);
    }
}
