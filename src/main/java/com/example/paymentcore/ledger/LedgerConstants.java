package com.example.paymentcore.ledger;

/** 阶段 2 固定的账套、政策与费率口径；真实实现必须由配置和合同驱动。 */
public final class LedgerConstants {

    public static final String BOOK_ID = "BOOK-1";
    public static final String POLICY_VERSION = "policy-v1";
    public static final String DEFAULT_CURRENCY = "CNY";

    /** 示例手续费 100 个基点（1%），向下取整，仅用于验证分录结构。 */
    public static final long FEE_BPS = 100;

    public static final String RECEIVABLE_CHANNEL = "RECEIVABLE_CHANNEL";
    public static final String MERCHANT_PAYABLE = "MERCHANT_PAYABLE";
    public static final String FEE_INCOME = "FEE_INCOME";
    public static final String BANK = "BANK";

    private LedgerConstants() {
    }
}
