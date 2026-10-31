package com.example.paymentcore.ledger;

import java.util.List;

/**
 * 记账请求。operationType + businessId 构成稳定业务动作身份；修正凭证必须引用原凭证。
 */
public record JournalRequest(String bookId, String merchantId, String operationType, String businessId,
                             String kind, String policyVersion, List<JournalEntryRequest> entries,
                             String correctionOfJournalId, String correctionReason, String approvedBy) {

    public static JournalRequest of(String bookId, String merchantId, String operationType, String businessId,
                                    String kind, List<JournalEntryRequest> entries) {
        return new JournalRequest(bookId, merchantId, operationType, businessId, kind,
                LedgerConstants.POLICY_VERSION, entries, null, null, null);
    }
}
