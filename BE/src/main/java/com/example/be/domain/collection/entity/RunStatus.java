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

    /** 진행 중 판정이 여러 곳에서 필요하다. 목록을 흩뿌리면 한 곳만 고치고 나머지를 빠뜨린다. */
    public static final java.util.Set<RunStatus> IN_PROGRESS_STATUSES = java.util.Set.of(PENDING, RUNNING);

    public boolean isInProgress() {
        return this == PENDING || this == RUNNING;
    }
}
