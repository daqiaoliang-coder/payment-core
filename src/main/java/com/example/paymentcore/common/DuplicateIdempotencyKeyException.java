package com.example.paymentcore.common;

/** 并发插入时由数据库唯一约束裁决出的幂等键冲突；抛出后当前事务回滚。 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(String message) {
        super(message);
    }
}
