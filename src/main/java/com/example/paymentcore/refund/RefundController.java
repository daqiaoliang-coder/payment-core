package com.example.paymentcore.refund;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 受理退款请求：只预留额度并建立独立退款身份，不在请求事务内调用渠道。 */
    @PostMapping
    public ResponseEntity<RefundView> create(@RequestHeader("X-Merchant-Id") String merchantId,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @Valid @RequestBody CreateRefundRequest request) {
        RefundView view = refundService.createRefund(merchantId, idempotencyKey, request.paymentId(),
                request.amountMinor());
        return ResponseEntity.accepted().body(view);
    }

    @GetMapping("/{refundId}")
    public RefundView get(@PathVariable String refundId) {
        return refundService.getRefund(refundId);
    }

    @GetMapping
    public List<RefundView> list(@RequestHeader("X-Merchant-Id") String merchantId,
                                 @RequestParam String paymentId) {
        return refundService.findByPayment(merchantId, paymentId);
    }
}
