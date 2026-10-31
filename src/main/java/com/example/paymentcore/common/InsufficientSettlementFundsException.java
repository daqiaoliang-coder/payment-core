package com.example.paymentcore.common;

/** 出款前资金准备检查不足；与账本余额不足区分，便于结算运营定位。 */
public class InsufficientSettlementFundsException extends ApiConflictException {

    public InsufficientSettlementFundsException(String message) {
        super("insufficient_settlement_funds", message);
    }
}
