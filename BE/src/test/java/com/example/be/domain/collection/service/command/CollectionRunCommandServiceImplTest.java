package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 요청 검증과 <b>유니크 위반 복구</b>만 본다. 생성 트랜잭션 자체는 {@link CollectionRunCreatorTest}에 있다.
 */
@ExtendWith(MockitoExtension.class)
class CollectionRunCommandServiceImplTest {

    @Mock
    private CollectionRunRepository runRepository;

    @Mock
    private CollectionRunCreator runCreator;

    @Mock
    private LlmPlanService planService;

    @Mock
    private AgentQuotaService quotaService;

    @InjectMocks
    private CollectionRunCommandServiceImpl runCommandService;

    @BeforeEach
    void setUpPlan() {
        lenient().when(planService.resolveRunPlan(any())).thenReturn(AgentPlan.FREE);
    }

    @Test
    void startManualRunDelegatesToCreatorWhenNoRunIsInProgress() {
        CollectionRunReqDTO.Create request = request(List.of(1L), " manual-key ");
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(runCreator.create(request, "manual-key", AgentPlan.FREE)).thenReturn(created(42L));

        CollectionRunStartResult result = runCommandService.startManualRun(request);

        assertEquals(GeneralSuccessCode.COLLECTION_STARTED, result.successCode());
        assertEquals(42L, result.response().getRunId());
    }

    @Test
    void startManualRunReturnsExistingRunWhenIdempotencyKeyIsAlreadyRunning() {
        when(runRepository.findInProgressByOptionalIdempotencyKey("same-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.of(running(42L, "same-key")));

        CollectionRunStartResult result = runCommandService.startManualRun(request(List.of(1L), "same-key"));

        assertEquals(GeneralSuccessCode.COLLECTION_ALREADY_RUNNING, result.successCode());
        assertEquals(42L, result.response().getRunId());
        assertNull(result.response().getTargetTopicIds());
        assertNull(result.response().getTargetCombinationCount());
        verify(runCreator, never()).create(any(), any(), any());
    }

    @Test
    void existingIdempotentRunIsReturnedBeforePlanValidation() {
        CollectionRunReqDTO.Create request = request(List.of(1L), "same-key");
        request.setPlan("INVALID");
        when(runRepository.findInProgressByOptionalIdempotencyKey("same-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.of(running(42L, "same-key")));

        CollectionRunStartResult result = runCommandService.startManualRun(request);

        assertEquals(GeneralSuccessCode.COLLECTION_ALREADY_RUNNING, result.successCode());
        verify(planService, never()).resolveRunPlan(any());
    }

    /**
     * ★ #31 A2. 같은 키 · 다른 주제 연타는 주제 잠금이 겹치지 않아 유니크 인덱스까지 간다.
     *
     * <p>예전에는 여기서 RUN409가 나갔다 — 제약 위반 시점에 영속성 컨텍스트가 롤백 표시돼
     * 같은 트랜잭션에서 이긴 실행을 조회할 수 없었기 때문이다. 생성을 별도 트랜잭션으로 떼어내
     * 명세대로 200 + 기존 run을 돌려준다.
     */
    @Test
    void startManualRunReturnsWinningRunWhenIdempotencyKeyRaceIsLost() {
        CollectionRunReqDTO.Create request = request(List.of(2L), "manual-key");
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(running(41L, "manual-key")));
        when(runCreator.create(request, "manual-key", AgentPlan.FREE))
                .thenThrow(new DuplicatedIdempotencyKeyException());

        CollectionRunStartResult result = runCommandService.startManualRun(request);

        assertEquals(GeneralSuccessCode.COLLECTION_ALREADY_RUNNING, result.successCode());
        assertEquals(41L, result.response().getRunId());
        assertEquals("RUNNING", result.response().getStatus());
        verify(runCreator, times(1)).create(request, "manual-key", AgentPlan.FREE);
    }

    /**
     * 위반과 재조회 사이에 이긴 실행이 끝나 버리면 키가 다시 비어 있다. "이미 실행 중"은 거짓이므로
     * 409로 닫지 않고 한 번 더 만든다.
     */
    @Test
    void startManualRunRetriesCreationWhenWinningRunAlreadyFinished() {
        CollectionRunReqDTO.Create request = request(List.of(2L), "manual-key");
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(runCreator.create(request, "manual-key", AgentPlan.FREE))
                .thenThrow(new DuplicatedIdempotencyKeyException())
                .thenReturn(created(43L));

        CollectionRunStartResult result = runCommandService.startManualRun(request);

        assertEquals(GeneralSuccessCode.COLLECTION_STARTED, result.successCode());
        assertEquals(43L, result.response().getRunId());
        verify(runCreator, times(2)).create(request, "manual-key", AgentPlan.FREE);
    }

    /**
     * 재시도까지 같은 키에 졌는데 그 실행도 조회되지 않으면 더 할 수 있는 게 없다.
     * 이때만 RUN409다 — 무한히 다시 시도하면 요청이 끝나지 않는다.
     */
    @Test
    void startManualRunConflictsWhenRetryLosesAndWinnerIsNotFound() {
        CollectionRunReqDTO.Create request = request(List.of(2L), "manual-key");
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(runCreator.create(request, "manual-key", AgentPlan.FREE))
                .thenThrow(new DuplicatedIdempotencyKeyException());

        RunException exception = assertThrows(RunException.class,
                () -> runCommandService.startManualRun(request));

        assertEquals(RunErrorCode.RUN_IN_PROGRESS, exception.getCode());
        verify(runCreator, times(2)).create(request, "manual-key", AgentPlan.FREE);
    }

    @Test
    void startManualRunRejectsTooLongIdempotencyKey() {
        String tooLong = "x".repeat(CollectionRun.MAX_IDEMPOTENCY_KEY_LENGTH + 1);

        GeneralException exception = assertThrows(GeneralException.class,
                () -> runCommandService.startManualRun(request(List.of(1L), tooLong)));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("idempotencyKey는 100자 이하여야 합니다.", exception.getMessage());
        verify(runRepository, never()).findInProgressByOptionalIdempotencyKey(any(), any());
    }

    /**
     * null이 섞인 topicIds를 그대로 내려보내면 대상 조회에서 의미 없는 조건이 되거나 NPE가 난다.
     */
    @Test
    void startManualRunRejectsNullInsideTopicIds() {
        CollectionRunReqDTO.Create request = request(new ArrayList<>(Arrays.asList(1L, null)), null);

        GeneralException exception = assertThrows(GeneralException.class,
                () -> runCommandService.startManualRun(request));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("topicIds에는 null을 넣을 수 없습니다.", exception.getMessage());
        verify(runCreator, never()).create(any(), any(), any());
    }

    /**
     * 요청 본문이 통째로 비어도 "활성 주제 전체 수집"으로 성립한다.
     */
    @Test
    void startManualRunAcceptsNullRequestBody() {
        when(runRepository.findInProgressByOptionalIdempotencyKey(eq(null), any())).thenReturn(Optional.empty());
        when(runCreator.create(any(), eq(null), eq(AgentPlan.FREE))).thenReturn(created(44L));

        CollectionRunStartResult result = runCommandService.startManualRun(null);

        assertEquals(GeneralSuccessCode.COLLECTION_STARTED, result.successCode());
        assertEquals(44L, result.response().getRunId());
    }

    private CollectionRunStartResult created(Long runId) {
        CollectionRunResDTO.Created response = CollectionRunResDTO.Created.builder()
                .runId(runId)
                .status(RunStatus.RUNNING.name())
                .triggerType(TriggerType.MANUAL.name())
                .build();
        return new CollectionRunStartResult(GeneralSuccessCode.COLLECTION_STARTED, response);
    }

    private CollectionRun running(Long runId, String idempotencyKey) {
        return CollectionRun.builder()
                .id(runId)
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey(idempotencyKey)
                .startedAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .build();
    }

    private CollectionRunReqDTO.Create request(List<Long> topicIds, String idempotencyKey) {
        CollectionRunReqDTO.Create request = new CollectionRunReqDTO.Create();
        request.setTopicIds(topicIds);
        request.setIdempotencyKey(idempotencyKey);
        request.setForceRefresh(false);
        return request;
    }
}
