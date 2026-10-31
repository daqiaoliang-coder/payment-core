package com.example.paymentcore.payment;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiNotFoundException;
import com.example.paymentcore.common.DuplicateIdempotencyKeyException;
import com.example.paymentcore.common.DuplicateMerchantOrderException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 对外用例编排：先做幂等预检，再以数据库唯一约束作为并发裁决；
 * 唯一约束冲突时事务回滚，随后读取已提交结果决定返回原结果还是参数冲突。
 */
@Service
public class PaymentService {

    public static final String OPERATION_TYPE = "CREATE_PAYMENT";
    public static final String NAMESPACE = "payment";

    private final PaymentRepository repository;
    private final PaymentTransactions transactions;

    public PaymentService(PaymentRepository repository, PaymentTransactions transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    public PaymentView createPayment(String merchantId, String idempotencyKey, String merchantOrderId,
                                     long amountMinor, String currency) {
        String normalizedCurrency = currency.toUpperCase(Locale.ROOT);
        String digest = digest(merchantOrderId, amountMinor, normalizedCurrency);

        PaymentRepository.IdempotencyRow existing = repository.findIdempotency(
                merchantId, OPERATION_TYPE, NAMESPACE, idempotencyKey);
        if (existing != null) {
            return resolveExisting(existing, digest);
        }

        try {
            return transactions.create(merchantId, OPERATION_TYPE, NAMESPACE, idempotencyKey,
                    merchantOrderId, amountMinor, normalizedCurrency, digest);
        } catch (DuplicateIdempotencyKeyException e) {
            PaymentRepository.IdempotencyRow winner = repository.findIdempotency(
                    merchantId, OPERATION_TYPE, NAMESPACE, idempotencyKey);
            if (winner == null) {
                throw e;
            }
            return resolveExisting(winner, digest);
        } catch (DuplicateMerchantOrderException e) {
            throw new ApiConflictException("merchant_order_conflict",
                    "商户订单号已绑定其他支付单，不能用新幂等键重复创建");
        }
    }

    public PaymentView getPayment(String paymentId) {
        PaymentView view = repository.findPayment(paymentId);
        if (view == null) {
            throw new ApiNotFoundException("支付单不存在: " + paymentId);
        }
        return view;
    }

    public List<PaymentView> findByMerchantOrder(String merchantId, String merchantOrderId) {
        return repository.findByMerchantOrder(merchantId, merchantOrderId);
    }

    public List<PaymentHistory> history(String paymentId) {
        getPayment(paymentId);
        return repository.findHistory(paymentId);
    }

    private PaymentView resolveExisting(PaymentRepository.IdempotencyRow row, String digest) {
        if (!row.payloadDigest().equals(digest)) {
            throw new ApiConflictException("idempotency_conflict",
                    "同一幂等键对应的金额、币种或商户订单号不一致");
        }
        PaymentView view = repository.findPayment(row.paymentId());
        if (view == null) {
            throw new IllegalStateException("幂等绑定引用的支付单不存在: " + row.paymentId());
        }
        return view;
    }

    private String digest(String merchantOrderId, long amountMinor, String currency) {
        String canonical = merchantOrderId + "|" + amountMinor + "|" + currency;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
