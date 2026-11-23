package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportMaintenanceServiceTest {
    private final ReportMaintenanceStore store = mock(ReportMaintenanceStore.class);
    private final AgentReportOrchestrator generator = mock(AgentReportOrchestrator.class);
    private final ReportMaintenanceService service = new ReportMaintenanceService(store, generator);
    private final LocalDateTime originalTime = LocalDateTime.of(2026, 11, 23, 9, 0);

    @Test void fallbackOrMockProviderResultNeverOverwritesSavedReport() {
        for (var status : List.of(ReportStatus.FALLBACK, ReportStatus.MOCK)) {
            var input = input(ReportStatus.FALLBACK);
            when(store.read(2310L)).thenReturn(input);
            when(generator.generateRecovery(any(), anyList(), eq(originalTime), eq("retry1")))
                    .thenReturn(document(status));

            var result = service.regenerateFallback(2310L, "revision", "retry1");

            assertFalse(result.replaced());
            assertSame(input.snapshot(), result.snapshot());
        }
        verify(store, never()).replace(anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test void successfulRecoveryUsesOriginalTimeAndPassesExplicitPromotionToGuardedWrite() {
        var input = input(ReportStatus.FALLBACK);
        when(store.read(2310L)).thenReturn(input);
        var document = document(ReportStatus.GENERATED);
        when(generator.generateRecovery(any(), anyList(), eq(originalTime), eq("retry1"))).thenReturn(document);
        var updated = new ReportMaintenanceService.Snapshot(2310L, 42L, "new", ReportStatus.GENERATED,
                originalTime, List.of("A", "B", "Claude"));
        when(store.replace(2310L, "revision", document, "Claude", 2)).thenReturn(updated);

        var result = service.regenerateFallback(2310L, "revision", "retry1", "Claude", 2);

        assertTrue(result.replaced());
        assertSame(updated, result.snapshot());
        verify(generator).generateRecovery(input.report().getRun(), input.view().findings(), originalTime, "retry1");
    }

    @Test void concurrentReportEditIsNotOverwrittenAfterProviderResponse() {
        var input = input(ReportStatus.FALLBACK);
        when(store.read(2310L)).thenReturn(input);
        var document = document(ReportStatus.GENERATED);
        when(generator.generateRecovery(any(), anyList(), any(), anyString())).thenReturn(document);
        when(store.replace(2310L, "revision", document, null, 0))
                .thenThrow(new IllegalStateException("changed"));
        assertThrows(IllegalStateException.class,
                () -> service.regenerateFallback(2310L, "revision", "retry1"));
    }

    @Test void invalidRevisionTargetOrRecoveryIdFailsBeforePaidWork() {
        when(store.read(2310L)).thenReturn(input(ReportStatus.FALLBACK));
        assertThrows(IllegalStateException.class,
                () -> service.regenerateFallback(2310L, "stale", "retry1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.regenerateFallback(2310L, "revision", "retry1", "missing", 2));
        assertThrows(IllegalArgumentException.class,
                () -> service.regenerateFallback(2310L, "revision", "bad:id"));
        verifyNoInteractions(generator);
    }

    @Test void alreadyGeneratedReportDoesNotReserveAnotherAttempt() {
        when(store.read(2310L)).thenReturn(input(ReportStatus.GENERATED));
        assertFalse(service.regenerateFallback(2310L, "revision", "retry1").replaced());
        verifyNoInteractions(generator);
    }

    @Test void localFeedbackExcludedEvidenceIsNeverPassedToProvider() {
        var input = input(ReportStatus.FALLBACK);
        var view = new ReportEventFeedbackProjection.View(input.view().findings(), input.view().readingContent(),
                true, Set.of(3L));
        when(store.read(2310L)).thenReturn(new ReportMaintenanceStore.Input(input.report(), view, input.snapshot()));
        when(generator.generateRecovery(any(), anyList(), any(), anyString())).thenReturn(document(ReportStatus.FALLBACK));

        service.regenerateFallback(2310L, "revision", "retry1");

        verify(generator).generateRecovery(eq(input.report().getRun()),
                argThat(values -> values.stream().map(Finding::getId).toList().equals(List.of(1L, 2L))),
                eq(originalTime), eq("retry1"));
    }

    private ReportMaintenanceStore.Input input(ReportStatus status) {
        var run = CollectionRun.builder().id(42L).status(RunStatus.SUCCESS).build();
        var content = new ReportContent(List.of("A", "B", "C"), List.of(event("A", 1), event("B", 2),
                event("Claude", 3)), List.of(), List.of());
        var report = NewsReport.builder().id(2310L).run(run).title("original").generatedAt(originalTime)
                .reportStatus(status).structuredContent(content).markdownBody("saved").build();
        var findings = List.of(Finding.builder().id(1L).build(), Finding.builder().id(2L).build(),
                Finding.builder().id(3L).build());
        return new ReportMaintenanceStore.Input(report, new ReportEventFeedbackProjection.View(findings,
                new ReportReadingContent("saved", content), false), new ReportMaintenanceService.Snapshot(
                2310L, 42L, "revision", status, originalTime, List.of("A", "B", "Claude")));
    }

    private ReportContent.ImportantEvent event(String title, long id) {
        return new ReportContent.ImportantEvent(title, "summary", "", List.of(id));
    }

    private ReportDocument document(ReportStatus status) {
        return new ReportDocument("new", "new body", "model", "prompt", "openai", 1L, 1L,
                null, null, status, List.of(1L, 2L, 3L), List.of());
    }
}
