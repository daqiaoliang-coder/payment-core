package com.example.paymentcore.domain;

/** Outbox 记录的本地发布进度。 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
