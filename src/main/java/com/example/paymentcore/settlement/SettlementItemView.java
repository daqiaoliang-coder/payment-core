package com.example.paymentcore.settlement;

/** 批次成员投影：每项关联清分明细与原业务身份。 */
public record SettlementItemView(String itemId, String entryId, String itemType, long amountMinor,
                                 long netMinor, String ruleVersion) {
}
