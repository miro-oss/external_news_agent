package com.example.be.domain.collection.entity;

/**
 * 실행을 누가 시작했는지. 스케줄러는 M3 범위 밖이라 지금은 {@link #MANUAL}만 만들어진다.
 */
public enum TriggerType {

    MANUAL,
    SCHEDULED
}
