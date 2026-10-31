package com.example.paymentcore.domain;

/** 预留状态；确认与释放在同一预留上互斥，且只能终结一次。 */
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}
