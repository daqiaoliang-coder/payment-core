package com.example.paymentcore.refund;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiForbiddenException;
import com.example.paymentcore.common.ApiNotFoundException;
import com.example.paymentcore.common.DuplicateRefundKeyException;
import com.example.paymentcore.domain.PaymentStatus;
import com.example.paymentcore.payment.PaymentRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 退款用例编排：只允许对已成功的原支付发起原路退款；
 * 幂等预检后用唯一约束裁决并发，唯一冲突时读取已提交结果决定返回原结果还是参数冲突。
 */
@Service
public class RefundService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final RefundTransactions transactions;

    public RefundService(PaymentRepository payments, RefundRepository refunds,
                         RefundTransactions transactions) {
        this.payments = payments;
        this.refunds = refunds;
        this.transactions = transactions;
    }

    public RefundView createRefund(String merchantId, String idempotencyKey, String paymentId, long amountMinor) {
        PaymentRepository.PaymentRow payment = payments.loadPaymentRow(paymentId);
        if (payment == null) {
            throw new ApiNotFoundException("支付单不存在: " + paymentId);
        }
        // L07：不得借用其他商户的支付单或账户
        if (!Objects.equals(payment.merchantId(), merchantId)) {
            throw new ApiForbiddenException("无权对该支付单发起退款");
        }
        if (!PaymentStatus.SUCCEEDED.name().equals(payment.status())) {
            throw new ApiConflictException("refund_not_allowed", "只有成功的原支付才能退款");
        }
        String currency = payment.currency().toUpperCase(Locale.ROOT);
        String digest = digest(paymentId, amountMinor, currency);

        RefundRepository.RefundIdempotencyRow existing = refunds.findIdempotency(merchantId, idempotencyKey);
        if (existing != null) {
            return resolveExisting(existing, digest);
        }
        try {
            return transactions.create(merchantId, idempotencyKey, digest, paymentId, amountMinor, currency,
                    payment.amountMinor());
        } catch (DuplicateRefundKeyException e) {
            RefundRepository.RefundIdempotencyRow winner = refunds.findIdempotency(merchantId, idempotencyKey);
            if (winner == null) {
                throw e;
            }
            return resolveExisting(winner, digest);
        }
    }

    public RefundView getRefund(String refundId) {
        RefundView view = refunds.view(refundId);
        if (view == null) {
            throw new ApiNotFoundException("退款单不存在: " + refundId);
        }
        return view;
    }

    public List<RefundView> findByPayment(String merchantId, String paymentId) {
        return refunds.findByPayment(paymentId, merchantId);
    }

    private RefundView resolveExisting(RefundRepository.RefundIdempotencyRow row, String digest) {
        if (!row.payloadDigest().equals(digest)) {
            throw new ApiConflictException("refund_idempotency_conflict",
                    "同一幂等键对应的支付单或金额不一致");
        }
        RefundView view = refunds.view(row.refundId());
        if (view == null) {
            throw new IllegalStateException("退款幂等绑定引用的退款单不存在: " + row.refundId());
        }
        return view;
    }

    private String digest(String paymentId, long amountMinor, String currency) {
        String canonical = paymentId + "|" + amountMinor + "|" + currency;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
