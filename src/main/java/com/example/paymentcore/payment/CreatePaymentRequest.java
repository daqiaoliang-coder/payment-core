package com.example.paymentcore.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 创建支付请求；金额使用整数最小货币单位。 */
public record CreatePaymentRequest(
        @NotBlank(message = "merchantOrderId 不能为空")
        String merchantOrderId,

        @NotNull(message = "amountMinor 不能为空")
        @Positive(message = "amountMinor 必须为正整数")
        Long amountMinor,

        @NotBlank(message = "currency 不能为空")
        @Pattern(regexp = "[A-Za-z]{3}", message = "currency 必须为三位字母代码")
        String currency) {
}
