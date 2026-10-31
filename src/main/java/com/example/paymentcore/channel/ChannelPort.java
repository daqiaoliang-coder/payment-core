package com.example.paymentcore.channel;

/**
 * 渠道端口：发送与查询都使用已持久化的渠道幂等键，不临时生成新身份。
 */
public interface ChannelPort {

    ChannelResult charge(String providerIdempotencyKey, String merchantOrderId, long amountMinor, String currency);

    ChannelResult query(String providerIdempotencyKey);

    ChannelResult refund(String providerIdempotencyKey, String merchantOrderId, long amountMinor, String currency);

    ChannelResult queryRefund(String providerIdempotencyKey);
}
