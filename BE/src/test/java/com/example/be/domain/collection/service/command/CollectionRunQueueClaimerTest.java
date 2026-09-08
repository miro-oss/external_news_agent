package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.repository.CollectionDispatchLockRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollectionRunQueueClaimerTest {
    @Mock private CollectionDispatchLockRepository gate;
    @Mock private CollectionRunRepository repository;
    @InjectMocks private CollectionRunQueueClaimer claimer;

    @Test
    void differentTopicsCanStartWhileAnOverlappingRequestWaits() {
        when(repository.countByStatus(RunStatus.RUNNING)).thenReturn(1L);
        when(repository.findTopicIdsByRunStatus(RunStatus.RUNNING)).thenReturn(List.of(1L));
        var overlapping = run(10L, 1L);
        var independent = run(11L, 2L);
        queue(overlapping, independent);
        assertEquals(List.of(11L), claimer.claimAvailable());
        assertEquals(RunStatus.PENDING, overlapping.getStatus());
        assertEquals(RunStatus.RUNNING, independent.getStatus());
        assertNotNull(independent.getStartedAt());
        assertEquals(RunItemStatus.RUNNING, independent.getItems().getFirst().getStatus());
        var order = inOrder(gate, repository);
        order.verify(gate).lockDispatcher();
        order.verify(repository).countByStatus(RunStatus.RUNNING);
    }

    @Test
    void sameTopicRequestsStartInOrderAndNeverAtTheSameTime() {
        var first = run(10L, 1L);
        var second = run(11L, 1L);
        queue(first, second);
        assertEquals(List.of(10L), claimer.claimAvailable());
        assertEquals(RunStatus.PENDING, second.getStatus());
    }

    @Test
    void doesNotExceedTheConfiguredConcurrency() {
        when(repository.countByStatus(RunStatus.RUNNING)).thenReturn(2L);
        assertTrue(claimer.claimAvailable().isEmpty());
        verify(repository, never()).findQueueIds(any(), any());
    }

    @Test
    void executorSaturationPreservesTheRequestForRetry() {
        var run = run(10L, 1L);
        run.start();
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(run));
        claimer.returnToQueue(10L);
        assertEquals(RunStatus.PENDING, run.getStatus());
        assertNull(run.getStartedAt());
        assertNotNull(run.getQueuedAt());
        assertEquals(RunItemStatus.PENDING, run.getItems().getFirst().getStatus());
    }

    @Test
    void dispatcherRequeuesRejectedTasksAndContinuesOtherClaims() {
        var claims = mock(CollectionRunQueueClaimer.class);
        var worker = mock(CollectionRunAsyncService.class);
        when(claims.claimAvailable()).thenReturn(List.of(10L, 11L));
        doThrow(new TaskRejectedException("full")).when(worker).execute(10L);
        new CollectionRunQueueDispatcher(claims, worker).dispatchAvailable();
        verify(claims).returnToQueue(10L);
        verify(worker).execute(11L);
    }

    private void queue(CollectionRun... runs) {
        when(repository.findQueueIds(eq(RunStatus.PENDING), any())).thenReturn(java.util.Arrays.stream(runs).map(CollectionRun::getId).toList());
        for (var run : runs) when(repository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
    }

    private CollectionRun run(long id, long topicId) {
        var run = CollectionRun.builder().id(id).status(RunStatus.PENDING).triggerType(TriggerType.MANUAL).build();
        run.addItem(CollectionRunItem.builder().topic(Topic.builder().id(topicId).build()).status(RunItemStatus.PENDING).build());
        return run;
    }
}
