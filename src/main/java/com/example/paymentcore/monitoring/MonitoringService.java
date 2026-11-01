package com.example.paymentcore.monitoring;

import com.example.paymentcore.domain.AttemptStatus;
import com.example.paymentcore.domain.PayoutStatus;
import com.example.paymentcore.domain.ReconDifferenceStatus;
import com.example.paymentcore.domain.RefundStatus;
import com.example.paymentcore.domain.ReservationStatus;
import com.example.paymentcore.domain.SettlementBatchStatus;
import com.example.paymentcore.ledger.LedgerAvailability;
import com.example.paymentcore.security.RecoveryGuardService;
import com.example.paymentcore.security.RecoveryLeaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 只读监控快照：暴露积压、UNKNOWN 账龄、预留占用、差错敞口与恢复门禁状态。
 * 指标按状态与币种计数，不拼出未经业务确认的阈值或 SLA。
 */
@Service
public class MonitoringService {

    public static final String STATUS_UP = "UP";
    public static final String STATUS_ATTENTION = "ATTENTION";
    public static final String STATUS_DEGRADED = "DEGRADED";

    public record AttemptBacklog(int created, int submitted, int processing, int unknown,
                                 Long oldestUnknownAgeSeconds) {
    }

    public record Snapshot(Instant asOf, int acceptedPayments, AttemptBacklog attempts, int pendingPosting,
                           int postingBlockedByLedger, int openRefundReservations, int openRefunds,
                           int unknownRefunds, int openSettlementBatches, int frozenSettlementBatches,
                           int openPayouts, int unknownPayouts, int openReconDifferences, int pendingOutbox,
                           boolean recoveryPaused, boolean recoveryLeaseHeld, Instant recoveryLeaseExpiresAt,
                           boolean ledgerAvailable) {
    }

    public record HealthView(String status, List<String> signals, Instant asOf) {
    }

    private final JdbcTemplate jdbc;
    private final RecoveryGuardService guard;
    private final LedgerAvailability ledgerAvailability;

    public MonitoringService(JdbcTemplate jdbc, RecoveryGuardService guard,
                             LedgerAvailability ledgerAvailability) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.ledgerAvailability = ledgerAvailability;
    }

    public Snapshot snapshot() {
        Instant now = Instant.now();
        Long oldestUnknownAge = jdbc.queryForObject(
                "SELECT DATEDIFF('SECOND', MIN(updated_at), CURRENT_TIMESTAMP) FROM channel_attempt "
                        + "WHERE status = ?", Long.class, AttemptStatus.UNKNOWN.name());
        Timestamp leaseExpiry = currentLeaseExpiry(now);
        return new Snapshot(now,
                count("SELECT COUNT(*) FROM payment_order WHERE status = 'ACCEPTED'"),
                new AttemptBacklog(
                        count("SELECT COUNT(*) FROM channel_attempt WHERE status = ?",
                                AttemptStatus.CREATED.name()),
                        count("SELECT COUNT(*) FROM channel_attempt WHERE status = ?",
                                AttemptStatus.SUBMITTED.name()),
                        count("SELECT COUNT(*) FROM channel_attempt WHERE status = ?",
                                AttemptStatus.PROCESSING.name()),
                        count("SELECT COUNT(*) FROM channel_attempt WHERE status = ?",
                                AttemptStatus.UNKNOWN.name()),
                        oldestUnknownAge),
                count("SELECT COUNT(*) FROM payment_posting WHERE status = 'PENDING'"),
                count("SELECT COUNT(*) FROM payment_posting WHERE status = 'LEDGER_UNAVAILABLE'"),
                count("SELECT COUNT(*) FROM refund_reservation WHERE status = ?",
                        ReservationStatus.RESERVED.name()),
                count("SELECT COUNT(*) FROM refund_order WHERE status IN (?, ?, ?)",
                        RefundStatus.ACCEPTED.name(), RefundStatus.PROCESSING.name(),
                        RefundStatus.UNKNOWN.name()),
                count("SELECT COUNT(*) FROM refund_order WHERE status = ?", RefundStatus.UNKNOWN.name()),
                count("SELECT COUNT(*) FROM settlement_batch WHERE status = ?",
                        SettlementBatchStatus.OPEN.name()),
                count("SELECT COUNT(*) FROM settlement_batch WHERE status = ?",
                        SettlementBatchStatus.FROZEN.name()),
                count("SELECT COUNT(*) FROM payout_order WHERE status IN (?, ?, ?)",
                        PayoutStatus.CREATED.name(), PayoutStatus.PROCESSING.name(),
                        PayoutStatus.UNKNOWN.name()),
                count("SELECT COUNT(*) FROM payout_order WHERE status = ?", PayoutStatus.UNKNOWN.name()),
                count("SELECT COUNT(*) FROM recon_difference WHERE status = ?",
                        ReconDifferenceStatus.OPEN.name()),
                count("SELECT COUNT(*) FROM outbox_record WHERE status = 'PENDING'"),
                guard.isPaused(RecoveryGuardService.PAYMENT_RECOVERY),
                leaseExpiry != null,
                leaseExpiry == null ? null : leaseExpiry.toInstant(),
                ledgerAvailability.isAvailable());
    }

    /** 健康状态只表达本地可判断的运行信号，不把积压量包装成 SLA。 */
    public HealthView health() {
        Snapshot snapshot = snapshot();
        List<String> degraded = new ArrayList<>();
        if (snapshot.recoveryPaused()) {
            degraded.add("recovery-paused");
        }
        if (!snapshot.ledgerAvailable()) {
            degraded.add("ledger-unavailable");
        }
        if (!degraded.isEmpty()) {
            return new HealthView(STATUS_DEGRADED, degraded, snapshot.asOf());
        }

        List<String> attention = new ArrayList<>();
        if (snapshot.attempts().unknown() > 0) {
            attention.add("unknown-attempts");
        }
        if (snapshot.postingBlockedByLedger() > 0) {
            attention.add("posting-blocked");
        }
        if (snapshot.unknownRefunds() > 0) {
            attention.add("unknown-refunds");
        }
        if (snapshot.unknownPayouts() > 0) {
            attention.add("unknown-payouts");
        }
        if (snapshot.openReconDifferences() > 0) {
            attention.add("open-recon-differences");
        }
        String status = attention.isEmpty() ? STATUS_UP : STATUS_ATTENTION;
        return new HealthView(status, attention, snapshot.asOf());
    }

    private Timestamp currentLeaseExpiry(Instant now) {
        List<Timestamp> rows = jdbc.queryForList("SELECT expires_at FROM recovery_lease "
                        + "WHERE task_name = ? AND expires_at > ?",
                Timestamp.class, RecoveryLeaseService.PAYMENT_RECOVERY_TASK, Timestamp.from(now));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
