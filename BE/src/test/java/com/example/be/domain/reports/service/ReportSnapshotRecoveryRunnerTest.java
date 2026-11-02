package com.example.be.domain.reports.service;

import com.example.be.global.config.ReportRecoveryMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportSnapshotRecoveryRunnerTest {
    private final ReportSnapshotRecoveryService service = mock(ReportSnapshotRecoveryService.class);
    private final ReportSnapshotRecoveryComparison comparisons = mock(ReportSnapshotRecoveryComparison.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final ReportSnapshotRecoveryRunner runner = new ReportSnapshotRecoveryRunner(service, comparisons, context);

    @Test void dryRunDoesNotEnqueueAndAlwaysClosesItsIsolatedApplication() {
        when(context.getEnvironment()).thenReturn(new MockEnvironment());
        when(service.recover(anyList(), eq(false), any())).thenReturn(List.of());
        runner.reportIds = "17,18,17";
        runner.run(null);
        verify(service).recover(eq(List.of(17L, 18L)), eq(false), any());
        verifyNoInteractions(comparisons);
        verify(context).close();
    }

    @Test void invalidOrMixedRecoveryModesNeverReachData() {
        when(context.getEnvironment()).thenReturn(new MockEnvironment()
                .withProperty(ReportRecoveryMode.RUN_IDS_PROPERTY, "99"));
        runner.reportIds = "17";
        assertThrows(IllegalArgumentException.class, () -> runner.run(null));
        verifyNoInteractions(service, comparisons);
        verify(context).close();
    }

    @Test void hiddenDependencyIsRepairedButNeverMadePublicOrCompared() {
        when(context.getEnvironment()).thenReturn(new MockEnvironment());
        when(service.recover(anyList(), eq(true), any())).thenReturn(List.of(
                new ReportSnapshotRecoveryService.Outcome(17, com.example.be.domain.reports.entity.ReportScope.DAILY,
                        null, true, true, true, null, 3, true),
                new ReportSnapshotRecoveryService.Outcome(18, com.example.be.domain.reports.entity.ReportScope.WEEKLY,
                        null, false, true, true, null, 3, true)));
        runner.reportIds = "18"; runner.apply = true;
        runner.run(null);
        verify(comparisons).complete(18);
        verify(comparisons, never()).complete(17);
        verify(context).close();
    }

    @Test void oneFailedComparisonDoesNotSkipLaterReportsAndAllFailuresAreReportedAtTheEnd() {
        when(context.getEnvironment()).thenReturn(new MockEnvironment());
        when(service.recover(anyList(), eq(true), any())).thenReturn(List.of(
                outcome(17), outcome(18), outcome(19), outcome(20)));
        var first = new IllegalStateException("first comparison unavailable");
        var later = new IllegalArgumentException("later stored input invalid");
        when(comparisons.complete(17)).thenThrow(first);
        when(comparisons.complete(18)).thenReturn(com.example.be.domain.reports.comparison.ReportChanges.Status.READY);
        when(comparisons.complete(19)).thenThrow(later);
        when(comparisons.complete(20)).thenReturn(com.example.be.domain.reports.comparison.ReportChanges.Status.FAILED);
        runner.reportIds = "17,18,19,20"; runner.apply = true;

        var failure = assertThrows(IllegalStateException.class, () -> runner.run(null));

        assertTrue(failure.getMessage().contains("[17, 19, 20]"));
        assertEquals(3, failure.getSuppressed().length);
        assertSame(first, failure.getSuppressed()[0]);
        assertSame(later, failure.getSuppressed()[1]);
        var order = inOrder(comparisons, context);
        order.verify(comparisons).complete(17);
        order.verify(comparisons).complete(18);
        order.verify(comparisons).complete(19);
        order.verify(comparisons).complete(20);
        order.verify(context).close();
        verify(service, times(1)).recover(eq(List.of(17L, 18L, 19L, 20L)), eq(true), any());
    }

    private ReportSnapshotRecoveryService.Outcome outcome(long id) {
        return new ReportSnapshotRecoveryService.Outcome(id, com.example.be.domain.reports.entity.ReportScope.DAILY,
                null, false, true, true, null, 3, true);
    }
}
