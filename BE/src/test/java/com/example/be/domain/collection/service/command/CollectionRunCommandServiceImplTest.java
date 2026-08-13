package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionRunCommandServiceImplTest {

    @Mock
    private TopicRepository topicRepository;

    @Mock
    private CollectionRunRepository runRepository;

    @Mock
    private CollectionRunItemRepository runItemRepository;

    @Mock
    private CollectionRunAsyncService runAsyncService;

    @Mock
    private CollectionResultWriter resultWriter;

    @InjectMocks
    private CollectionRunCommandServiceImpl runCommandService;

    @Test
    void startManualRunCreatesRunWithTargetItemsAndSchedulesAsyncExecution() {
        CollectionRunReqDTO.Create request = request(List.of(1L, 1L, 2L), " manual-key ", true);
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L, 2L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(10L)), target(topic(2L, "DRAM"), source(11L))));
        when(runRepository.findInProgressByTopicIds(List.of(1L, 2L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class))).thenAnswer(invocation -> {
            CollectionRun run = invocation.getArgument(0);
            return CollectionRun.builder()
                    .id(42L)
                    .status(run.getStatus())
                    .triggerType(run.getTriggerType())
                    .idempotencyKey(run.getIdempotencyKey())
                    .forceRefresh(run.isForceRefresh())
                    .startedAt(run.getStartedAt())
                    .build();
        });

        CollectionRunStartResult result = runCommandService.startManualRun(request);

        assertEquals(GeneralSuccessCode.COLLECTION_STARTED, result.successCode());
        assertEquals(42L, result.response().getRunId());
        assertEquals("RUNNING", result.response().getStatus());
        assertEquals("manual-key", result.response().getIdempotencyKey());
        assertEquals(List.of(1L, 2L), result.response().getTargetTopicIds());
        assertEquals(2, result.response().getTargetCombinationCount());
        verify(runAsyncService).execute(42L);

        ArgumentCaptor<CollectionRun> captor = ArgumentCaptor.forClass(CollectionRun.class);
        verify(runRepository).saveAndFlush(captor.capture());
        CollectionRun saved = captor.getValue();
        assertEquals(RunStatus.RUNNING, saved.getStatus());
        assertEquals(TriggerType.MANUAL, saved.getTriggerType());
        assertTrue(saved.isForceRefresh());
        assertEquals(2, saved.getItems().size());
        assertEquals(RunItemStatus.RUNNING, saved.getItems().get(0).getStatus());
    }

    @Test
    void startManualRunReturnsExistingRunWhenIdempotencyKeyIsAlreadyRunning() {
        CollectionRun existing = CollectionRun.builder()
                .id(42L)
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey("same-key")
                .startedAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .build();
        when(runRepository.findInProgressByOptionalIdempotencyKey("same-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.of(existing));

        CollectionRunStartResult result = runCommandService.startManualRun(request(List.of(1L), "same-key", false));

        assertEquals(GeneralSuccessCode.COLLECTION_ALREADY_RUNNING, result.successCode());
        assertEquals(42L, result.response().getRunId());
        assertNull(result.response().getTargetTopicIds());
        assertNull(result.response().getTargetCombinationCount());
        verify(topicRepository, never()).findActiveCollectionTargetsByTopicIds(any());
        verify(runRepository, never()).saveAndFlush(any());
        verify(runAsyncService, never()).execute(any());
    }

    @Test
    void startManualRunRejectsWhenNoTargetCombinationExists() {
        when(runRepository.findInProgressByOptionalIdempotencyKey(null, RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargets()).thenReturn(List.of());

        RunException exception = assertThrows(RunException.class,
                () -> runCommandService.startManualRun(request(null, null, false)));

        assertEquals(RunErrorCode.NO_TARGET_COMBINATION, exception.getCode());
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void startManualRunRejectsWhenAnotherRunCollectsSameTopic() {
        when(runRepository.findInProgressByOptionalIdempotencyKey(null, RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(10L))));
        CollectionRun conflict = CollectionRun.builder().id(41L).status(RunStatus.RUNNING).build();
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of(conflict));
        when(runItemRepository.findTopicIdsByRunIdInAndTopicIdIn(List.of(41L), List.of(1L)))
                .thenReturn(List.of(1L));

        RunException exception = assertThrows(RunException.class,
                () -> runCommandService.startManualRun(request(List.of(1L), null, false)));

        assertEquals(RunErrorCode.RUN_IN_PROGRESS, exception.getCode());
        assertEquals(41L, exception.getResult().get("conflictRunId"));
        assertEquals(List.of(1L), exception.getResult().get("conflictTopicIds"));
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void startManualRunRejectsTooLongIdempotencyKey() {
        String tooLong = "x".repeat(CollectionRun.MAX_IDEMPOTENCY_KEY_LENGTH + 1);

        GeneralException exception = assertThrows(GeneralException.class,
                () -> runCommandService.startManualRun(request(List.of(1L), tooLong, false)));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("idempotencyKey는 100자 이하여야 합니다.", exception.getMessage());
        verify(runRepository, never()).findInProgressByOptionalIdempotencyKey(any(), any());
    }

    private CollectionRunReqDTO.Create request(List<Long> topicIds, String idempotencyKey, boolean forceRefresh) {
        CollectionRunReqDTO.Create request = new CollectionRunReqDTO.Create();
        request.setTopicIds(topicIds);
        request.setIdempotencyKey(idempotencyKey);
        request.setForceRefresh(forceRefresh);
        return request;
    }

    private Topic topic(Long id, String name) {
        return Topic.builder()
                .id(id)
                .name(name)
                .queryText(name)
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build();
    }

    private Source source(Long id) {
        return Source.builder()
                .id(id)
                .sourceKind(Source.KIND_FEED)
                .name("Source " + id)
                .urlTemplate("https://example.com/" + id)
                .active(true)
                .build();
    }

    private TopicRepository.CollectionTarget target(Topic topic, Source source) {
        return new TopicRepository.CollectionTarget() {
            @Override
            public Topic getTopic() {
                return topic;
            }

            @Override
            public Source getSource() {
                return source;
            }
        };
    }

    /**
     * 같은 키가 거의 동시에 들어오면 앞의 조회로는 못 막는다 — 첫 요청이 아직 커밋되기 전이다.
     * DB 유니크 인덱스가 잡아 주는데, 그 위반을 번역하지 않으면 사용자에게 500이 나간다.
     */
    @Test
    void startManualRunTranslatesActiveIdempotencyKeyViolationToConflict() {
        CollectionRunReqDTO.Create request = request(List.of(1L), "manual-key", false);
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement [ORA-00001: UQ_RUN_ACTIVE_IDEMPOTENCY_KEY]"));

        RunException exception = assertThrows(RunException.class,
                () -> runCommandService.startManualRun(request));

        assertEquals(RunErrorCode.RUN_IN_PROGRESS, exception.getCode());
        verify(runAsyncService, never()).execute(any());
    }

    /**
     * idempotencyKey와 무관한 제약 위반까지 409로 바꾸면 진짜 원인이 가려진다.
     */
    @Test
    void startManualRunRethrowsUnrelatedConstraintViolation() {
        CollectionRunReqDTO.Create request = request(List.of(1L), "manual-key", false);
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenThrow(new DataIntegrityViolationException("ORA-00001: UQ_RUN_ITEM"));

        assertThrows(DataIntegrityViolationException.class,
                () -> runCommandService.startManualRun(request));
    }

    /**
     * 충돌 검사와 실행 생성 사이에 다른 요청이 끼어들면 같은 주제를 동시에 수집하게 된다.
     * idempotencyKey와 달리 DB 제약으로 막을 수 없어 대상 주제를 먼저 잠근다.
     */
    @Test
    void startManualRunLocksTargetTopicsBeforeCheckingConflict() {
        CollectionRunReqDTO.Create request = request(List.of(1L), "manual-key", false);
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        runCommandService.startManualRun(request);

        InOrder order = inOrder(topicRepository, runRepository);
        order.verify(topicRepository).lockByIds(List.of(1L));
        order.verify(runRepository).findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES);
    }

    /**
     * 실행 행은 이미 RUNNING으로 커밋돼 있다. 스레드풀이 거절하면 아무도 그 행을 닫지 않아
     * 영원히 RUNNING으로 남고, 그 주제는 충돌 검사에 걸려 다시 실행할 수도 없게 된다.
     */
    @Test
    void startManualRunFailsTheRunWhenExecutorRejectsTheTask() {
        CollectionRunReqDTO.Create request = request(List.of(1L), "manual-key", false);
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new TaskRejectedException("queue full")).when(runAsyncService).execute(any());

        runCommandService.startManualRun(request);

        verify(resultWriter).failRun(any());
    }

    /**
     * 충돌한 실행이 여럿이면 주제도 여럿이다. run A가 1을, run B가 2를 수집 중인데 [1, 2]를 요청하면
     * 둘 다 알려줘야 한다. 대표 실행 하나만 보고 계산하면 [1]만 내려간다.
     */
    @Test
    void startManualRunReportsEveryConflictingTopic() {
        when(runRepository.findInProgressByOptionalIdempotencyKey(null, RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L, 2L)))
                .thenReturn(List.of(
                        target(topic(1L, "HBM"), source(10L)),
                        target(topic(2L, "DRAM"), source(11L))));
        CollectionRun first = CollectionRun.builder().id(41L).status(RunStatus.RUNNING).build();
        CollectionRun second = CollectionRun.builder().id(42L).status(RunStatus.RUNNING).build();
        when(runRepository.findInProgressByTopicIds(List.of(1L, 2L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of(second, first));
        when(runItemRepository.findTopicIdsByRunIdInAndTopicIdIn(List.of(42L, 41L), List.of(1L, 2L)))
                .thenReturn(List.of(1L, 2L));

        RunException exception = assertThrows(RunException.class,
                () -> runCommandService.startManualRun(request(List.of(1L, 2L), null, false)));

        // conflictRunId는 단수라 가장 먼저 시작한 실행이 대표다.
        assertEquals(41L, exception.getResult().get("conflictRunId"));
        assertEquals(List.of(1L, 2L), exception.getResult().get("conflictTopicIds"));
    }

    /**
     * 버튼 연타는 앞의 조회로 못 막는다 — 첫 요청이 아직 커밋되기 전이다. 주제를 잠근 뒤 키를 다시 보면
     * 그때는 커밋돼 있어서, 명세대로 200 + 기존 run을 돌려줄 수 있다.
     */
    @Test
    void startManualRunReturnsRunningRunWhenKeyAppearsAfterLock() {
        CollectionRun running = CollectionRun.builder()
                .id(41L)
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey("manual-key")
                .startedAt(LocalDateTime.now())
                .build();
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(running));
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(10L))));

        CollectionRunStartResult result =
                runCommandService.startManualRun(request(List.of(1L), "manual-key", false));

        assertEquals(GeneralSuccessCode.COLLECTION_ALREADY_RUNNING, result.successCode());
        assertEquals(41L, result.response().getRunId());
        verify(runRepository, never()).saveAndFlush(any());
        verify(runAsyncService, never()).execute(any());
    }
}
