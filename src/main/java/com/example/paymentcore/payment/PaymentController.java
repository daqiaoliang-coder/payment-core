package com.example.paymentcore.payment;

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
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 受理收款请求；只创建本地意图，不在请求事务内调用渠道。 */
    @PostMapping
    public ResponseEntity<PaymentView> create(@RequestHeader("X-Merchant-Id") String merchantId,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @Valid @RequestBody CreatePaymentRequest request) {
        PaymentView view = paymentService.createPayment(merchantId, idempotencyKey,
                request.merchantOrderId(), request.amountMinor(), request.currency());
        return ResponseEntity.accepted().body(view);
    }

    @GetMapping("/{paymentId}")
    public PaymentView get(@PathVariable String paymentId) {
        return paymentService.getPayment(paymentId);
    }

    @GetMapping
    public List<PaymentView> list(@RequestHeader("X-Merchant-Id") String merchantId,
                                  @RequestParam String merchantOrderId) {
        return paymentService.findByMerchantOrder(merchantId, merchantOrderId);
    }

    @GetMapping("/{paymentId}/events")
    public List<PaymentHistory> events(@PathVariable String paymentId) {
        return paymentService.history(paymentId);
    }
}
