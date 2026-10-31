package com.example.paymentcore.common;

/** 证据与本地状态矛盾（金额币种不符、终态收到相反证据等），不得静默覆盖。 */
public class EvidenceConflictException extends RuntimeException {

    public EvidenceConflictException(String message) {
        super(message);
    }
}
