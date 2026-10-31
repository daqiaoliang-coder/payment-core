package com.example.paymentcore.refund;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 创建退款请求；只能引用已存在的支付单，币种由原支付决定。 */
public record CreateRefundRequest(
        @NotBlank(message = "paymentId 不能为空") String paymentId,
        @NotNull(message = "amountMinor 不能为空")
        @Positive(message = "amountMinor 必须为正整数") Long amountMinor) {
}
