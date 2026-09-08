package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.notifications.service.CollectionRunDeliveryService;
import com.example.be.domain.notifications.service.RunDeliverySnapshotStore;
import com.example.be.domain.notifications.service.ReportNotificationAutomationService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollectionRunCreatorTest {
    @Mock private TopicRepository topicRepository;
    @Mock private CollectionRunRepository runRepository;
    @Mock private CollectionRunDeliveryService delivery;
    @InjectMocks private CollectionRunCreator creator;

    @Test
    void persistsTheWholeRequestBeforeAnyWorkerStarts() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L, 2L)))
                .thenReturn(List.of(target(1L), target(2L)));
        when(runRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        CollectionRunReqDTO.Create request = request(List.of(1L, 1L, 2L));
        request.setForceRefresh(true);

        var result = creator.create(request, "queue-request", AgentPlan.FREE);

        assertEquals(GeneralSuccessCode.COLLECTION_QUEUED, result.successCode());
        assertEquals("PENDING", result.response().getStatus());
        assertNotNull(result.response().getQueuedAt());
        assertNull(result.response().getStartedAt());
        assertEquals(List.of(1L, 2L), result.response().getTargetTopicIds());
        ArgumentCaptor<CollectionRun> saved = ArgumentCaptor.forClass(CollectionRun.class);
        verify(runRepository).saveAndFlush(saved.capture());
        assertTrue(saved.getValue().isForceRefresh());
        assertEquals(2, saved.getValue().getItems().size());
        assertTrue(saved.getValue().getItems().stream().allMatch(item -> item.getStatus() == RunItemStatus.PENDING));
        assertTrue(saved.getValue().getItems().stream().allMatch(item -> item.getTopicSnapshot() != null));
        var snapshot = saved.getValue().getItems().getFirst().getTopicSnapshot();
        assertEquals("주제 1", snapshot.topicName());
        assertEquals(List.of("HBM"), snapshot.requiredKeywords());
        assertEquals("HBM 1", saved.getValue().getItems().getFirst().collectionTopic().getQueryText());
        verify(runRepository, never()).findInProgressByTopicIds(any(), any());
    }

    @Test
    void sameRequestKeyReturnsAnExistingQueuedRun() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L))).thenReturn(List.of(target(1L)));
        when(runRepository.findInProgressByOptionalIdempotencyKey("same", RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(Optional.of(CollectionRun.builder().id(5L).status(RunStatus.PENDING)
                        .triggerType(TriggerType.MANUAL).idempotencyKey("same").build()));
        var replay = request(List.of(1L));
        replay.setDelivery(new CollectionRunReqDTO.Delivery());
        var result = creator.create(replay, "same", AgentPlan.FREE);
        assertEquals(GeneralSuccessCode.COLLECTION_ALREADY_RUNNING, result.successCode());
        assertEquals(5L, result.response().getRunId());
        assertEquals("PENDING", result.response().getStatus());
        verify(runRepository, never()).saveAndFlush(any());
        verifyNoInteractions(delivery);
    }

    @Test
    void validatesDeliveryBeforeCreatingRunAndSavesTheSnapshotInsideItsCreationBoundary() {
        var request = request(List.of(1L));
        request.setDelivery(new CollectionRunReqDTO.Delivery());
        var prepared = new CollectionRunDeliveryService.Prepared(
                new ReportNotificationAutomationService.Policy(false, true, false, List.of(), List.of(), List.of()),
                new RunDeliverySnapshotStore.Snapshot("ONCE", false, true, false, List.of()));
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L))).thenReturn(List.of(target(1L)));
        when(delivery.prepare(request.getDelivery())).thenReturn(prepared);
        when(runRepository.saveAndFlush(any())).thenReturn(CollectionRun.builder().id(42L).status(RunStatus.PENDING).triggerType(TriggerType.MANUAL).build());

        creator.create(request, "delivery", AgentPlan.FREE);

        var order = inOrder(delivery, runRepository);
        order.verify(runRepository).findInProgressByOptionalIdempotencyKey("delivery", RunStatus.IN_PROGRESS_STATUSES);
        order.verify(delivery).prepare(request.getDelivery());
        order.verify(runRepository).saveAndFlush(any());
        order.verify(delivery).save(42L, List.of(1L), prepared);
    }

    @Test
    void failedDeliveryValidationDoesNotCreateAQueuedRun() {
        var request = request(List.of(1L));
        request.setDelivery(new CollectionRunReqDTO.Delivery());
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L))).thenReturn(List.of(target(1L)));
        when(delivery.prepare(request.getDelivery())).thenThrow(new IllegalArgumentException("invalid selection"));
        assertThrows(IllegalArgumentException.class, () -> creator.create(request, "invalid-delivery", AgentPlan.FREE));
        verify(runRepository, never()).saveAndFlush(any());
        verify(delivery, never()).save(any(), any(), any());
    }

    @Test
    void snapshotFailureRollsBackTheCreationTransactionInsteadOfCommittingTheRun() {
        var request = request(List.of(1L));
        request.setDelivery(new CollectionRunReqDTO.Delivery());
        var prepared = new CollectionRunDeliveryService.Prepared(
                new ReportNotificationAutomationService.Policy(false, true, false, List.of(), List.of(), List.of()),
                new RunDeliverySnapshotStore.Snapshot("TOPIC", false, true, false, List.of()));
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L))).thenReturn(List.of(target(1L)));
        when(delivery.prepare(request.getDelivery())).thenReturn(prepared);
        when(runRepository.saveAndFlush(any())).thenReturn(CollectionRun.builder().id(42L).status(RunStatus.PENDING).build());
        doThrow(new DataIntegrityViolationException("snapshot failure")).when(delivery).save(42L, List.of(1L), prepared);
        var transactions = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        var proxy = new ProxyFactory(creator);
        var transactionAdvice = new TransactionInterceptor();
        transactionAdvice.setTransactionManager(transactions);
        transactionAdvice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(transactionAdvice);

        assertThrows(DataIntegrityViolationException.class,
                () -> ((CollectionRunCreator) proxy.getProxy()).create(request, "atomic-delivery", AgentPlan.FREE));

        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void rejectsAnEmptyTarget() {
        when(topicRepository.findActiveCollectionTargets()).thenReturn(List.of());
        assertEquals(RunErrorCode.NO_TARGET_COMBINATION,
                assertThrows(RunException.class, () -> creator.create(request(null), null, AgentPlan.FREE)).getCode());
    }

    @Test
    void scheduledRequestsAreQueuedOnlyOnceWhilePending() {
        Topic topic = target(1L).getTopic();
        var now = LocalDateTime.of(2026, 9, 8, 10, 0);
        when(topicRepository.lockByIds(List.of(1L))).thenReturn(List.of(topic));
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L))).thenReturn(List.of(target(1L)));
        when(runRepository.findInProgressByTopicIds(List.of(1L), RunStatus.IN_PROGRESS_STATUSES))
                .thenReturn(List.of(CollectionRun.builder().status(RunStatus.PENDING).build()));
        assertFalse(creator.createScheduled(1L, AgentPlan.FREE, now));
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void scheduledRequestHasNoActualStartTimeUntilClaimed() {
        Topic topic = target(1L).getTopic();
        var now = LocalDateTime.of(2026, 9, 8, 10, 0);
        when(topicRepository.lockByIds(List.of(1L))).thenReturn(List.of(topic));
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L))).thenReturn(List.of(target(1L)));
        when(runRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        assertTrue(creator.createScheduled(1L, AgentPlan.FREE, now));
        ArgumentCaptor<CollectionRun> saved = ArgumentCaptor.forClass(CollectionRun.class);
        verify(runRepository).saveAndFlush(saved.capture());
        assertEquals(now, saved.getValue().getQueuedAt());
        assertNull(saved.getValue().getStartedAt());
        assertNull(topic.getLastCollectedAt());
        var snapshot = saved.getValue().getItems().getFirst().getTopicSnapshot();
        assertNotNull(snapshot);
        assertEquals("주제 1", snapshot.topicName());
        assertEquals("HBM 1", snapshot.queryText());
        assertEquals(List.of("HBM"), snapshot.requiredKeywords());
    }

    @Test
    void preservesUniqueKeyRaceRecoveryWithoutSwallowingOtherConstraints() {
        when(topicRepository.findActiveCollectionTargetsByTopicIds(List.of(1L))).thenReturn(List.of(target(1L)));
        when(runRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("UQ_RUN_ACTIVE_IDEMPOTENCY_KEY"));
        assertThrows(DuplicatedIdempotencyKeyException.class,
                () -> creator.create(request(List.of(1L)), "same", AgentPlan.FREE));
        doThrow(new DataIntegrityViolationException("OTHER_CONSTRAINT")).when(runRepository).saveAndFlush(any());
        assertThrows(DataIntegrityViolationException.class,
                () -> creator.create(request(List.of(1L)), "same", AgentPlan.FREE));
    }

    private CollectionRunReqDTO.Create request(List<Long> ids) {
        var request = new CollectionRunReqDTO.Create();
        request.setTopicIds(ids);
        return request;
    }

    private TopicRepository.CollectionTarget target(long id) {
        Topic topic = Topic.builder().id(id).name("주제 " + id).queryText("HBM " + id)
                .requiredKeywords(List.of("HBM")).optionalKeywords(List.of()).excludedKeywords(List.of())
                .batchSize(10).intervalMinutes(1440).active(true).build();
        Source source = Source.builder().id(id).sourceKind(Source.KIND_FEED).name("소스")
                .urlTemplate("https://example.com/feed").active(true).build();
        return new TopicRepository.CollectionTarget() {
            public Topic getTopic() { return topic; }
            public Source getSource() { return source; }
        };
    }
}
