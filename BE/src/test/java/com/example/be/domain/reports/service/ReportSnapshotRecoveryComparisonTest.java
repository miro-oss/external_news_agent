package com.example.be.domain.reports.service;

import com.example.be.domain.reports.comparison.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportSnapshotRecoveryComparisonTest {
    @Test void malformedStoredInputDoesNotLeaveAClaimRunning() {
        var persistence = mock(ReportComparisonPersistence.class);
        var repository = mock(ReportComparisonRepository.class);
        var engine = mock(ReportComparisonEngine.class);
        var result = new ReportChanges(17, null, 16L, null, ReportChanges.Status.PENDING,
                "준비 중", false, List.of(), List.of());
        when(persistence.claim(17)).thenReturn(Optional.of(new ReportComparisonRepository.Job(
                17, ReportChanges.Status.RUNNING, null, result)));
        when(engine.prepare(null)).thenThrow(new IllegalArgumentException("invalid stored input"));

        assertThrows(IllegalStateException.class,
                () -> new ReportSnapshotRecoveryComparison(persistence, repository, engine).complete(17));

        verify(persistence).finish(eq(17L), argThat(value -> value.status() == ReportChanges.Status.FAILED));
    }
}
