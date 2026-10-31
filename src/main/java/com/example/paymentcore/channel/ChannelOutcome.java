package com.example.paymentcore.channel;

/** 渠道返回结果的本地分类；TIMEOUT 与 NOT_FOUND 均不构成失败终态。 */
public enum ChannelOutcome {
    SUCCESS,
    PROCESSING,
    FAILED,
    TIMEOUT,
    NOT_FOUND
}
