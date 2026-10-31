package com.example.paymentcore.recon;

/** 银行流水视图：保留总额、已分配额与剩余额，防止同一流水被重复消费。 */
public record StatementView(String statementId, String provider, String bankReference, String direction,
                            long amountMinor, long allocatedMinor, String currency, String status) {
}
