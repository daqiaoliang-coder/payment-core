package com.example.paymentcore.payment;

import com.example.paymentcore.domain.PaymentStatus;
import com.example.paymentcore.domain.PostingJobStatus;
import com.example.paymentcore.ledger.JournalEntryRequest;
import com.example.paymentcore.ledger.JournalRequest;
import com.example.paymentcore.ledger.JournalResult;
import com.example.paymentcore.ledger.LedgerAccountService;
import com.example.paymentcore.ledger.LedgerAvailability;
import com.example.paymentcore.ledger.LedgerConstants;
import com.example.paymentcore.ledger.LedgerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付成功待记账：读取 SUCCEEDED 且未记账的支付单，用稳定业务键提交凭证。
 * 账本不可用时保留 PENDING 状态，绝不把支付成功改成失败（M10）。
 */
@Service
public class PaymentPostingService {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final PaymentPostingTransactions transactions;
    private final LedgerService ledger;
    private final LedgerAccountService accounts;
    private final LedgerAvailability availability;

    public PaymentPostingService(JdbcTemplate jdbc, PaymentRepository payments,
                                 PaymentPostingTransactions transactions, LedgerService ledger,
                                 LedgerAccountService accounts, LedgerAvailability availability) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.transactions = transactions;
        this.ledger = ledger;
        this.accounts = accounts;
        this.availability = availability;
    }

    public int postPending() {
        if (!availability.isAvailable()) {
            transactions.markLedgerUnavailable();
            return 0;
        }
        List<String> paymentIds = jdbc.queryForList(
                "SELECT payment_id FROM payment_posting WHERE status IN (?, ?) "
                        + "ORDER BY updated_at LIMIT " + BATCH_LIMIT,
                String.class, PostingJobStatus.PENDING.name(), PostingJobStatus.LEDGER_UNAVAILABLE.name());

        int posted = 0;
        for (String paymentId : paymentIds) {
            PaymentRepository.PaymentRow payment = payments.loadPaymentRow(paymentId);
            if (payment == null || !PaymentStatus.SUCCEEDED.name().equals(payment.status())) {
                continue;
            }
            long fee = payment.amountMinor() * LedgerConstants.FEE_BPS / 10_000;
            long net = payment.amountMinor() - fee;
            List<JournalEntryRequest> entries = new ArrayList<>();
            entries.add(new JournalEntryRequest(
                    accounts.receivableChannel(LedgerConstants.BOOK_ID, payment.currency()),
                    "DEBIT", payment.amountMinor()));
            entries.add(new JournalEntryRequest(
                    accounts.merchantPayable(LedgerConstants.BOOK_ID, payment.merchantId(), payment.currency()),
                    "CREDIT", net));
            if (fee > 0) {
                entries.add(new JournalEntryRequest(
                        accounts.feeIncome(LedgerConstants.BOOK_ID, payment.currency()), "CREDIT", fee));
            }

            JournalResult result = ledger.postJournal(JournalRequest.of(
                    LedgerConstants.BOOK_ID, payment.merchantId(), "PAYMENT_POSTING", paymentId,
                    "PAYMENT_CONFIRMED", entries));
            transactions.markPosted(paymentId, result.journalId());
            posted++;
        }
        return posted;
    }
}
