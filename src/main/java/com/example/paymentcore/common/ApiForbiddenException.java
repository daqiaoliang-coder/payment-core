package com.example.paymentcore.common;

/** 账户归属或商户边界越权，映射为 HTTP 403。 */
public class ApiForbiddenException extends RuntimeException {

    public ApiForbiddenException(String message) {
        super(message);
    }
}
