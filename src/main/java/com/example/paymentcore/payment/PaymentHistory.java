package com.example.paymentcore.payment;

import java.time.Instant;

/** 状态历史条目，按写入顺序返回，不按通知到达顺序重排。 */
public record PaymentHistory(long historyId, String status, String detail, Instant createdAt) {
}
