package com.example.paymentcore.common;

/** 并发插入账本业务操作时由唯一约束裁决；抛出后当前事务回滚。 */
public class DuplicateLedgerOperationException extends RuntimeException {

    public DuplicateLedgerOperationException(String message) {
        super(message);
    }
}
