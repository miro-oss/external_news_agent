package com.example.be.domain.collection.entity;

/**
 * 조합(주제 × 소스) 하나의 결과. 소스가 비활성이거나 304로 바뀐 게 없으면 {@link #SKIPPED}다.
 */
public enum RunItemStatus {

    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILED,
    SKIPPED
}
