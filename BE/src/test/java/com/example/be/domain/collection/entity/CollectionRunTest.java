package com.example.be.domain.collection.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CollectionRunTest {

    private static final LocalDateTime FINISHED_AT = LocalDateTime.of(2026, 8, 14, 9, 0);

    @Test
    void abortClosesUnfinishedItemsAndMarksRunFailedWhenNothingSucceeded() {
        CollectionRun run = run();
        run.addItem(item(RunItemStatus.RUNNING));
        run.addItem(item(RunItemStatus.PENDING));

        run.abort(FINISHED_AT);

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(FINISHED_AT, run.getFinishedAt());
        run.getItems().forEach(item -> assertEquals(RunItemStatus.FAILED, item.getStatus()));
    }

    /**
     * ★ 중간에 끊긴 실행을 통째로 FAILED로 적으면 앞에서 성공한 수집이 이력에서 사라진다.
     * 안 끝난 조합만 닫고 상태는 finish()가 계산한다.
     */
    @Test
    void abortKeepsAlreadySucceededItemsAndMarksRunPartial() {
        CollectionRun run = run();
        CollectionRunItem succeeded = item(RunItemStatus.RUNNING);
        succeeded.recordResult(RunItemStatus.SUCCESS, 10, 4, 1);
        run.addItem(succeeded);
        run.addItem(item(RunItemStatus.RUNNING));

        run.abort(FINISHED_AT);

        assertEquals(RunStatus.PARTIAL, run.getStatus());
        assertEquals(RunItemStatus.SUCCESS, run.getItems().get(0).getStatus());
        assertEquals(RunItemStatus.FAILED, run.getItems().get(1).getStatus());
        assertEquals(10, run.getScannedCount());
        assertEquals(4, run.getNewCount());
        assertEquals(1, run.getUpdatedCount());
        assertEquals(5, run.getSkippedCount());
    }

    /**
     * 이미 끝난 조합만 있으면 abort는 finish와 같다. reaper가 경합으로 두 번 불려도 이력이 망가지지 않는다.
     */
    @Test
    void abortOnAlreadyTerminalItemsBehavesLikeFinish() {
        CollectionRun run = run();
        CollectionRunItem succeeded = item(RunItemStatus.RUNNING);
        succeeded.recordResult(RunItemStatus.SUCCESS, 3, 3, 0);
        run.addItem(succeeded);

        run.abort(FINISHED_AT);

        assertEquals(RunStatus.SUCCESS, run.getStatus());
        assertEquals(3, run.getScannedCount());
    }

    /**
     * 아직 도는 조합이 있는데 그냥 닫으면 SUCCESS로 기록될 수 있다. finish는 그걸 거부한다 —
     * abort를 쓰라는 뜻이다.
     */
    @Test
    void finishRefusesToCloseWhileItemsAreStillRunning() {
        CollectionRun run = run();
        run.addItem(item(RunItemStatus.RUNNING));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> run.finish(FINISHED_AT));

        assertNotNull(exception.getMessage());
    }

    @Test
    void finishMarksRunPartialWhenRunLevelWarningExistsWithoutItems() {
        CollectionRun run = run();
        run.addWarning(CollectionRunWarning.builder()
                .code(CollectionRunWarning.CODE_REPORT_GENERATION_FAILED)
                .message("보고서 생성 실패")
                .articleCount(0)
                .occurredAt(FINISHED_AT)
                .build());

        run.finish(FINISHED_AT);

        assertEquals(RunStatus.PARTIAL, run.getStatus());
    }

    private CollectionRun run() {
        return CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .startedAt(LocalDateTime.of(2026, 8, 14, 8, 0))
                .build();
    }

    private CollectionRunItem item(RunItemStatus status) {
        return CollectionRunItem.builder().status(status).build();
    }
}
