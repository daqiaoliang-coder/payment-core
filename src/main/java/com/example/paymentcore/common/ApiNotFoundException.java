package com.example.paymentcore.common;

/** 资源不存在，映射为 HTTP 404。 */
public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }
}
