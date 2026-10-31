package com.example.paymentcore.channel;

/**
 * 渠道结果。成功证据必须携带金额、币种与渠道引用，供核心核对后确认事实。
 */
public record ChannelResult(ChannelOutcome outcome, String providerReference, long amountMinor, String currency,
                            String detail) {

    public static ChannelResult success(String providerReference, long amountMinor, String currency) {
        return new ChannelResult(ChannelOutcome.SUCCESS, providerReference, amountMinor, currency, "channel succeeded");
    }

    public static ChannelResult processing(String providerReference, long amountMinor, String currency) {
        return new ChannelResult(ChannelOutcome.PROCESSING, providerReference, amountMinor, currency, "channel processing");
    }

    public static ChannelResult failed(String detail) {
        return new ChannelResult(ChannelOutcome.FAILED, null, 0L, null, detail);
    }

    public static ChannelResult timeout(String detail) {
        return new ChannelResult(ChannelOutcome.TIMEOUT, null, 0L, null, detail);
    }

    public static ChannelResult notFound(String detail) {
        return new ChannelResult(ChannelOutcome.NOT_FOUND, null, 0L, null, detail);
    }
}
