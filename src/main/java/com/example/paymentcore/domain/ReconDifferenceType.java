package com.example.paymentcore.domain;

/** 逐笔核对差异类型；汇总相等不能掩盖正负差错抵销。 */
public enum ReconDifferenceType {
    MISSING_IN_REPORT,
    EXTRA_IN_REPORT,
    AMOUNT_MISMATCH
}
