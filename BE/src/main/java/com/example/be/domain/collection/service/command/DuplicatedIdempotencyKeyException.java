package com.example.be.domain.collection.service.command;

/**
 * 진행 중 유니크 인덱스({@code UQ_RUN_ACTIVE_IDEMPOTENCY_KEY})에 걸렸다는 내부 신호.
 *
 * <p>API로 새어 나가지 않는다 — {@link CollectionRunCommandServiceImpl}이 잡아서 기존 실행 조회로 바꾼다.
 * 그래서 {@code RunErrorCode}가 아니라 이 패키지 안에서만 쓰는 예외다.
 */
class DuplicatedIdempotencyKeyException extends RuntimeException {

    DuplicatedIdempotencyKeyException() {
        super("같은 idempotencyKey의 실행이 먼저 커밋됐다.");
    }
}
