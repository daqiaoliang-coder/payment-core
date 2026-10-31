package com.example.paymentcore.ledger;

/** 分录请求；币种由账户决定，避免同一分录被请求参数改写币种。 */
public record JournalEntryRequest(String accountId, String side, long amountMinor) {
}
