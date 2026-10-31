package com.example.paymentcore.common;

/** 同一商户订单号已绑定其他收款意图；抛出后当前事务回滚。 */
public class DuplicateMerchantOrderException extends RuntimeException {

    public DuplicateMerchantOrderException(String message) {
        super(message);
    }
}
