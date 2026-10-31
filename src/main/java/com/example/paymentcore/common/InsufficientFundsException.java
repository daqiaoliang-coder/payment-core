package com.example.paymentcore.common;

/** 禁止透支账户在原子条件更新中余额不足。 */
public class InsufficientFundsException extends ApiConflictException {

    public InsufficientFundsException(String message) {
        super("insufficient_funds", message);
    }
}
