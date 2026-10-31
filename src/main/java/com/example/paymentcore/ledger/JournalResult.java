package com.example.paymentcore.ledger;

/** 记账结果；created=false 表示命中既有业务操作，未产生第二份凭证。 */
public record JournalResult(String journalId, boolean created) {
}
