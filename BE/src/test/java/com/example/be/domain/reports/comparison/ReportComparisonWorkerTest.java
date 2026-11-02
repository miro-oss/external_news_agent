package com.example.be.domain.reports.comparison;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static com.example.be.domain.reports.comparison.ComparisonFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportComparisonWorkerTest {
    private final ReportComparisonRepository repository = mock(ReportComparisonRepository.class);
    private final ReportComparisonPersistence persistence = mock(ReportComparisonPersistence.class);
    private final ReportComparisonAnalyzer analyzer = mock(ReportComparisonAnalyzer.class);
    private final ReportComparisonEngine engine = new ReportComparisonEngine();
    private final ReportComparisonWorker worker = new ReportComparisonWorker(repository, persistence, engine, analyzer);

    private ReportComparisonRepository.Job job(long reportId) {
        var work = work(snapshot(side(1, 10, "하반기 양산")), snapshot(side(1, 20, "10월 양산")));
        return new ReportComparisonRepository.Job(reportId, ReportChanges.Status.RUNNING, work,
                engine.prepare(work).result().withStatus(ReportChanges.Status.PENDING));
    }

    @Test void lostClaimsNeverCallProviderAndCompletedJobsAreNeverReplayed() {
        when(persistence.claim(101)).thenReturn(Optional.empty());
        worker.process(101);
        verifyNoInteractions(analyzer);
        verify(persistence, never()).finish(anyLong(), any());
    }

    @Test void failureIsTerminalAndDoesNotPreventOtherQueuedReports() {
        when(repository.pending()).thenReturn(List.of(101L, 102L));
        when(persistence.claim(101)).thenReturn(Optional.of(job(101)));
        when(persistence.claim(102)).thenReturn(Optional.of(job(102)));
        when(analyzer.analyze(eq(101L), anyLong(), anyList())).thenThrow(new IllegalStateException("unknown provider outcome"));
        when(analyzer.analyze(eq(102L), anyLong(), anyList())).thenReturn(List.of());
        worker.poll();
        var result = ArgumentCaptor.forClass(ReportChanges.class);
        verify(persistence).finish(eq(101L), result.capture());
        assertEquals(ReportChanges.Status.FAILED, result.getValue().status());
        verify(persistence).finish(eq(102L), argThat(value -> value.status() == ReportChanges.Status.READY));
        verify(repository).expireRunning(any(), any());
        verify(analyzer, times(1)).analyze(eq(101L), anyLong(), anyList());
    }

    @Test void bothSchedulingSwitchesPreventClaims() {
        ReflectionTestUtils.setField(worker, "enabled", false);
        worker.poll();
        verifyNoInteractions(repository, persistence, analyzer);
        ReflectionTestUtils.setField(worker, "enabled", true);
        ReflectionTestUtils.setField(worker, "schedulingEnabled", false);
        worker.poll();
        verifyNoInteractions(repository, persistence, analyzer);
    }

    @Test void registeringAfterCompletionIsFailureIsolated() {
        doThrow(new IllegalStateException("comparison store unavailable")).when(persistence).enqueue(101);
        assertDoesNotThrow(() -> worker.completed(new ReportCompleted(101)));
    }

    @Test void moreThanOnePageWaitingForBaselinesCannotStarveLaterWorkAndAreRetriedOnWrap() {
        var missing = java.util.stream.LongStream.rangeClosed(1, 13).boxed().toList();
        var queued = new java.util.concurrent.atomic.AtomicBoolean();
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        when(repository.missingJobs(anyLong())).thenAnswer(call -> missing.stream()
                .filter(id -> id > (long) call.getArgument(0) && (id != 13L || !completed.get()))
                .limit(10).toList());
        // The first twelve reports cannot register a job while their exact baseline is PENDING.
        doAnswer(call -> { if ((long) call.getArgument(0) == 13L) queued.set(true); return null; })
                .when(persistence).enqueue(anyLong());
        when(repository.pending()).thenAnswer(call -> queued.get() ? List.of(13L) : List.of());
        when(persistence.claim(13L)).thenReturn(Optional.of(job(13L)));
        when(analyzer.analyze(eq(13L), anyLong(), anyList())).thenReturn(List.of());
        doAnswer(call -> { completed.set(true); queued.set(false); return null; })
                .when(persistence).finish(eq(13L), any());

        worker.poll();
        verify(persistence, never()).enqueue(13L);
        worker.poll();

        verify(persistence).enqueue(11L);
        verify(persistence).enqueue(12L);
        verify(persistence).finish(eq(13L), argThat(result -> result.status() == ReportChanges.Status.READY));
        verify(persistence, never()).claim(1L);
        worker.poll(); // Reaching the end resets the cursor without manufacturing baseline results.
        worker.poll();
        verify(persistence, times(2)).enqueue(1L);
        verify(persistence, times(1)).enqueue(13L);
        verify(analyzer, times(1)).analyze(eq(13L), anyLong(), anyList());
    }
}
