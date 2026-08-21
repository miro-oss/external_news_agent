package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실행 생성 트랜잭션. 대상 조회 · 주제 잠금 · 충돌 검사 · 저장까지가 여기 책임이다.
 * 유니크 위반을 200으로 되돌리는 복구는 {@link CollectionRunCommandServiceImpl} 쪽 테스트에 있다.
 */
@ExtendWith(MockitoExtension.class)
class CollectionRunCreatorTest {

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
    private CollectionRunCreator runCreator;

    @Test
    void createBuildsRunWithTargetItemsAndSchedulesAsyncExecution() {
        CollectionRunReqDTO.Create request = request(List.of(1L, 1L, 2L), true);
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L, 2L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(10L)), target(topic(2L, "DRAM"), source(11L))));
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
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

        CollectionRunStartResult result = runCreator.create(request, "manual-key", AgentPlan.FREE);

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
    void createRejectsWhenNoTargetCombinationExists() {
        when(topicRepository.findActiveCollectionTargets()).thenReturn(List.of());

        RunException exception = assertThrows(RunException.class,
                () -> runCreator.create(request(null, false), null, AgentPlan.FREE));

        assertEquals(RunErrorCode.NO_TARGET_COMBINATION, exception.getCode());
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsWhenAnotherRunCollectsSameTopic() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(10L))));
        CollectionRun conflict = CollectionRun.builder().id(41L).status(RunStatus.RUNNING).build();
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of(conflict));
        when(runItemRepository.findTopicIdsByRunIdInAndTopicIdIn(List.of(41L), List.of(1L)))
                .thenReturn(List.of(1L));

        RunException exception = assertThrows(RunException.class,
                () -> runCreator.create(request(List.of(1L), false), null, AgentPlan.FREE));

        assertEquals(RunErrorCode.RUN_IN_PROGRESS, exception.getCode());
        assertEquals(41L, exception.getResult().get("conflictRunId"));
        assertEquals(List.of(1L), exception.getResult().get("conflictTopicIds"));
        verify(runRepository, never()).saveAndFlush(any());
    }

    /**
     * 충돌한 실행이 여럿이면 주제도 여럿이다. run A가 1을, run B가 2를 수집 중인데 [1, 2]를 요청하면
     * 둘 다 알려줘야 한다. 대표 실행 하나만 보고 계산하면 [1]만 내려간다.
     */
    @Test
    void createReportsEveryConflictingTopic() {
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
                () -> runCreator.create(request(List.of(1L, 2L), false), null, AgentPlan.FREE));

        // conflictRunId는 단수라 가장 먼저 시작한 실행이 대표다.
        assertEquals(41L, exception.getResult().get("conflictRunId"));
        assertEquals(List.of(1L, 2L), exception.getResult().get("conflictTopicIds"));
    }

    /**
     * 충돌 검사와 실행 생성 사이에 다른 요청이 끼어들면 같은 주제를 동시에 수집하게 된다.
     * idempotencyKey와 달리 DB 제약으로 막을 수 없어 대상 주제를 먼저 잠근다.
     */
    @Test
    void createLocksTargetTopicsBeforeCheckingConflict() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        runCreator.create(request(List.of(1L), false), "manual-key", AgentPlan.FREE);

        InOrder order = inOrder(topicRepository, runRepository);
        order.verify(topicRepository).lockByIds(List.of(1L));
        order.verify(runRepository).findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES);
    }

    /**
     * 대상 주제가 겹치는 연타는 잠금을 잡은 뒤 키를 다시 보는 것만으로 잡힌다 — 그 시점에는
     * 먼저 들어온 실행이 이미 커밋돼 있다. 저장까지 가지 않으므로 유니크 위반도 나지 않는다.
     */
    @Test
    void createReturnsRunningRunWhenKeyAppearsAfterLock() {
        CollectionRun running = CollectionRun.builder()
                .id(41L)
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey("manual-key")
                .startedAt(LocalDateTime.now())
                .build();
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(10L))));
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.of(running));

        CollectionRunStartResult result =
                runCreator.create(request(List.of(1L), false), "manual-key", AgentPlan.FREE);

        assertEquals(GeneralSuccessCode.COLLECTION_ALREADY_RUNNING, result.successCode());
        assertEquals(41L, result.response().getRunId());
        verify(runRepository, never()).saveAndFlush(any());
        verify(runAsyncService, never()).execute(any());
    }

    /**
     * 같은 키 · 다른 주제는 잠금이 겹치지 않아 저장까지 간다. DB 유니크 인덱스가 잡아 주는데,
     * 그 위반을 내부 신호로 번역해야 호출자가 200으로 되돌릴 수 있다.
     */
    @Test
    void createSignalsDuplicatedKeyOnActiveIdempotencyKeyViolation() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement [ORA-00001: UQ_RUN_ACTIVE_IDEMPOTENCY_KEY]"));

        assertThrows(DuplicatedIdempotencyKeyException.class,
                () -> runCreator.create(request(List.of(1L), false), "manual-key", AgentPlan.FREE));

        verify(runAsyncService, never()).execute(any());
    }

    /**
     * idempotencyKey와 무관한 제약 위반까지 삼키면 진짜 원인이 가려진다.
     */
    @Test
    void createRethrowsUnrelatedConstraintViolation() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenThrow(new DataIntegrityViolationException("ORA-00001: UQ_RUN_ITEM"));

        assertThrows(DataIntegrityViolationException.class,
                () -> runCreator.create(request(List.of(1L), false), "manual-key", AgentPlan.FREE));
    }

    /**
     * 실행 행은 이미 RUNNING으로 커밋돼 있다. 스레드풀이 거절하면 아무도 그 행을 닫지 않아
     * 영원히 RUNNING으로 남고, 그 주제는 충돌 검사에 걸려 다시 실행할 수도 없게 된다.
     */
    @Test
    void createFailsTheRunWhenExecutorRejectsTheTask() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L)))
                .thenReturn(List.of(target(topic(1L, "HBM"), source(1L))));
        when(runRepository.findInProgressByOptionalIdempotencyKey("manual-key", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.empty());
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of());
        when(runRepository.saveAndFlush(any(CollectionRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new TaskRejectedException("queue full")).when(runAsyncService).execute(any());

        runCreator.create(request(List.of(1L), false), "manual-key", AgentPlan.FREE);

        verify(resultWriter).failRun(any());
    }

    @Test
    void failRunUsesNewTransactionForAfterCommitFailureHandling() throws NoSuchMethodException {
        Method method = CollectionResultWriter.class.getMethod("failRun", Long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    private CollectionRunReqDTO.Create request(List<Long> topicIds, boolean forceRefresh) {
        CollectionRunReqDTO.Create request = new CollectionRunReqDTO.Create();
        request.setTopicIds(topicIds);
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
}
