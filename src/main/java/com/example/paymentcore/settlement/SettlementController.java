package com.example.paymentcore.settlement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 清分、批次与出款入口。渠道结算与商户出款是两件事，此处不自动触发付款。 */
@RestController
@RequestMapping("/api/settlement")
public class SettlementController {

    public record OpenBatchRequest(@NotBlank String merchantId, String currency, String ruleVersion) {
    }

    public record PaymentRequest(@NotBlank String paymentId) {
    }

    public record RefundRequest(@NotBlank String refundId) {
    }

    public record ReturnRequest(@NotBlank String reason, @NotBlank String approvedBy) {
    }

    private final SettlementService settlement;
    private final PayoutService payout;

    public SettlementController(SettlementService settlement, PayoutService payout) {
        this.settlement = settlement;
        this.payout = payout;
    }

    @PostMapping("/batches")
    public BatchView openBatch(@Valid @RequestBody OpenBatchRequest request) {
        return settlement.openBatch(request.merchantId(), request.currency(), request.ruleVersion());
    }

    @PostMapping("/batches/{batchId}/payments")
    public SettlementItemView addPayment(@PathVariable String batchId,
                                         @Valid @RequestBody PaymentRequest request) {
        return settlement.addPayment(batchId, request.paymentId());
    }

    @PostMapping("/batches/{batchId}/refunds")
    public SettlementItemView addRefund(@PathVariable String batchId,
                                        @Valid @RequestBody RefundRequest request) {
        return settlement.addRefund(batchId, request.refundId());
    }

    @PostMapping("/batches/{batchId}/freeze")
    public BatchView freeze(@PathVariable String batchId) {
        return settlement.freeze(batchId);
    }

    @GetMapping("/batches/{batchId}")
    public BatchView batch(@PathVariable String batchId) {
        return settlement.getBatch(batchId);
    }

    @GetMapping("/batches/{batchId}/items")
    public List<SettlementItemView> items(@PathVariable String batchId) {
        return settlement.items(batchId);
    }

    @PostMapping("/batches/{batchId}/payout")
    public PayoutView createPayout(@PathVariable String batchId) {
        return payout.createPayout(batchId);
    }

    @GetMapping("/payouts/{payoutId}")
    public PayoutView payout(@PathVariable String payoutId) {
        return payout.getPayout(payoutId);
    }

    @PostMapping("/payouts/{payoutId}/return")
    public PayoutView markReturned(@PathVariable String payoutId,
                                   @Valid @RequestBody ReturnRequest request) {
        return payout.markReturned(payoutId, request.reason(), request.approvedBy());
    }
}
