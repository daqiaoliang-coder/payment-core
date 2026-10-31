package com.example.paymentcore.common;

/** 并发创建退款时由唯一约束裁决；抛出后当前事务回滚。 */
public class DuplicateRefundKeyException extends RuntimeException {

    public DuplicateRefundKeyException(String message) {
        super(message);
    }
}
