package com.example.be.domain.collection.entity;

/**
 * 수집 실행의 상태. news_collection_runs.ck_run_status와 값이 일치해야 한다.
 *
 * <p>크롤러 실패는 실행 전체를 실패로 만들지 않는다. 일부만 실패하면 {@link #PARTIAL}이고 사유는 경고로 쌓인다.
 * 전부 실패했을 때만 {@link #FAILED}다.
 */
public enum RunStatus {

    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL,
    FAILED;

    public boolean isInProgress() {
        return this == PENDING || this == RUNNING;
    }
}
