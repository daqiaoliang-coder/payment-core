package com.example.paymentcore.payment;

import com.example.paymentcore.outbox.OutboxPublisher;
import com.example.paymentcore.refund.RefundRecoveryService;
import com.example.paymentcore.refund.RefundSender;
import com.example.paymentcore.settlement.PayoutRecoveryService;
import com.example.paymentcore.settlement.PayoutSender;
import org.springframework.stereotype.Service;

/** 同步驱动发送、恢复、记账、退款、出款与发布，便于确定性测试（不依赖睡眠或真实调度）。 */
@Service
public class TickService {

    public record TickResult(int sent, int recovered, int posted, int refundsSent, int refundsRecovered,
                             int payoutsSent, int payoutsRecovered, int published) {
    }

    private final AttemptSender sender;
    private final RecoveryService recovery;
    private final PaymentPostingService posting;
    private final RefundSender refundSender;
    private final RefundRecoveryService refundRecovery;
    private final PayoutSender payoutSender;
    private final PayoutRecoveryService payoutRecovery;
    private final OutboxPublisher publisher;

    public TickService(AttemptSender sender, RecoveryService recovery, PaymentPostingService posting,
                       RefundSender refundSender, RefundRecoveryService refundRecovery,
                       PayoutSender payoutSender, PayoutRecoveryService payoutRecovery,
                       OutboxPublisher publisher) {
        this.sender = sender;
        this.recovery = recovery;
        this.posting = posting;
        this.refundSender = refundSender;
        this.refundRecovery = refundRecovery;
        this.payoutSender = payoutSender;
        this.payoutRecovery = payoutRecovery;
        this.publisher = publisher;
    }

    public TickResult tick() {
        int sent = sender.sendPending();
        int recovered = recovery.recoverOpenAttempts();
        int posted = posting.postPending();
        int refundsSent = refundSender.sendPending();
        int refundsRecovered = refundRecovery.recoverOpenRefunds();
        int payoutsSent = payoutSender.sendPending();
        int payoutsRecovered = payoutRecovery.recoverOpenPayouts();
        int published = publisher.publishPending();
        return new TickResult(sent, recovered, posted, refundsSent, refundsRecovered, payoutsSent,
                payoutsRecovered, published);
    }
}
