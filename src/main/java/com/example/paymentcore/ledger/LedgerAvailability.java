package com.example.paymentcore.ledger;

import org.springframework.stereotype.Component;

/** 本地模拟的账本可用性开关，用于验证“账本不可用不改写支付成功事实”。 */
@Component
public class LedgerAvailability {

    private volatile boolean available = true;

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }
}
