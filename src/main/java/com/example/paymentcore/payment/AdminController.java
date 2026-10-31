package com.example.paymentcore.payment;

import com.example.paymentcore.bank.MockBank;
import com.example.paymentcore.channel.MockChannel;
import com.example.paymentcore.domain.BankScenario;
import com.example.paymentcore.domain.ChannelScenario;
import com.example.paymentcore.ledger.BalanceProjectionService;
import com.example.paymentcore.ledger.LedgerAvailability;
import com.example.paymentcore.ledger.LedgerQuery;
import com.example.paymentcore.outbox.OutboxPublisher;
import com.example.paymentcore.refund.RefundRecoveryService;
import com.example.paymentcore.refund.RefundSender;
import com.example.paymentcore.settlement.PayoutRecoveryService;
import com.example.paymentcore.settlement.PayoutSender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 本地验证入口：设置模拟渠道场景，并同步驱动发送、核查、记账、退款与发布。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record ScenarioRequest(@NotBlank String merchantOrderId, @NotNull ChannelScenario scenario) {
    }

    public record BankScenarioRequest(@NotBlank String merchantId, @NotNull BankScenario scenario) {
    }

    public record LedgerAvailabilityRequest(@NotNull Boolean available) {
    }

    private final MockChannel mockChannel;
    private final MockBank mockBank;
    private final AttemptSender sender;
    private final RecoveryService recovery;
    private final OutboxPublisher publisher;
    private final TickService tickService;
    private final PaymentPostingService posting;
    private final RefundSender refundSender;
    private final RefundRecoveryService refundRecovery;
    private final PayoutSender payoutSender;
    private final PayoutRecoveryService payoutRecovery;
    private final LedgerAvailability ledgerAvailability;
    private final BalanceProjectionService projection;
    private final LedgerQuery ledgerQuery;

    public AdminController(MockChannel mockChannel, MockBank mockBank, AttemptSender sender, RecoveryService recovery,
                           OutboxPublisher publisher, TickService tickService, PaymentPostingService posting,
                           RefundSender refundSender, RefundRecoveryService refundRecovery,
                           PayoutSender payoutSender, PayoutRecoveryService payoutRecovery,
                           LedgerAvailability ledgerAvailability, BalanceProjectionService projection,
                           LedgerQuery ledgerQuery) {
        this.mockChannel = mockChannel;
        this.mockBank = mockBank;
        this.sender = sender;
        this.recovery = recovery;
        this.publisher = publisher;
        this.tickService = tickService;
        this.posting = posting;
        this.refundSender = refundSender;
        this.refundRecovery = refundRecovery;
        this.payoutSender = payoutSender;
        this.payoutRecovery = payoutRecovery;
        this.ledgerAvailability = ledgerAvailability;
        this.projection = projection;
        this.ledgerQuery = ledgerQuery;
    }

    @PostMapping("/channel-scenarios")
    public Map<String, String> setScenario(@Valid @RequestBody ScenarioRequest request) {
        mockChannel.setScenario(request.merchantOrderId(), request.scenario());
        return Map.of("merchantOrderId", request.merchantOrderId(), "scenario", request.scenario().name());
    }

    @PostMapping("/bank-scenarios")
    public Map<String, String> setBankScenario(@Valid @RequestBody BankScenarioRequest request) {
        mockBank.setScenario(request.merchantId(), request.scenario());
        return Map.of("merchantId", request.merchantId(), "scenario", request.scenario().name());
    }

    @PostMapping("/payout-send")
    public Map<String, Integer> payoutSend() {
        return Map.of("payoutsSent", payoutSender.sendPending());
    }

    @PostMapping("/payout-recover")
    public Map<String, Integer> payoutRecover() {
        return Map.of("payoutsRecovered", payoutRecovery.recoverOpenPayouts());
    }

    @PostMapping("/send")
    public Map<String, Integer> send() {
        return Map.of("sent", sender.sendPending());
    }

    @PostMapping("/recover")
    public Map<String, Integer> recover() {
        return Map.of("recovered", recovery.recoverOpenAttempts());
    }

    @PostMapping("/publish")
    public Map<String, Integer> publish() {
        return Map.of("published", publisher.publishPending());
    }

    @PostMapping("/post")
    public Map<String, Integer> post() {
        return Map.of("posted", posting.postPending());
    }

    @PostMapping("/refund-send")
    public Map<String, Integer> refundSend() {
        return Map.of("refundsSent", refundSender.sendPending());
    }

    @PostMapping("/refund-recover")
    public Map<String, Integer> refundRecover() {
        return Map.of("refundsRecovered", refundRecovery.recoverOpenRefunds());
    }

    @PostMapping("/ledger-availability")
    public Map<String, Boolean> ledgerAvailability(@Valid @RequestBody LedgerAvailabilityRequest request) {
        ledgerAvailability.setAvailable(request.available());
        return Map.of("available", ledgerAvailability.isAvailable());
    }

    @PostMapping("/projection/rebuild")
    public Map<String, Long> rebuildProjection() {
        projection.rebuild();
        return Map.of("sourceSequence", ledgerQuery.sourceSequence());
    }

    @GetMapping("/ledger/balance")
    public LedgerQuery.BalanceState balance(@RequestParam String accountId) {
        return ledgerQuery.balance(accountId);
    }

    @PostMapping("/tick")
    public TickService.TickResult tick() {
        return tickService.tick();
    }
}
