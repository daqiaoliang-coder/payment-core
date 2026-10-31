package com.example.paymentcore.common;

/** 幂等键、商户订单号或证据状态冲突，映射为 HTTP 409。 */
public class ApiConflictException extends RuntimeException {

    private final String code;

    public ApiConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
