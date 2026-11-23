package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.ReportStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Explicit operations for one saved report; intentionally not exposed by a background scheduler. */
@Service
@RequiredArgsConstructor
public class ReportMaintenanceService {
    private final ReportMaintenanceStore store;
    private final AgentReportOrchestrator generator;

    public Snapshot inspect(long reportId) {
        return store.read(reportId).snapshot();
    }

    public Snapshot promote(long reportId, String expectedFingerprint, String exactEventTitle, int briefingIndex) {
        return store.promote(reportId, expectedFingerprint, exactEventTitle, briefingIndex);
    }

    public RegenerationResult regenerateFallback(long reportId, String expectedFingerprint, String recoveryId) {
        return regenerateFallback(reportId, expectedFingerprint, recoveryId, null, 0);
    }

    public RegenerationResult regenerateFallback(long reportId, String expectedFingerprint, String recoveryId,
                                                  String exactEventTitle, int briefingIndex) {
        if (recoveryId == null || !recoveryId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("올바른 보고서 복구 ID가 필요합니다.");
        var input = store.read(reportId);
        ReportMaintenanceStore.requireFingerprint(input, expectedFingerprint);
        if (input.snapshot().status() != ReportStatus.FALLBACK)
            return new RegenerationResult(false, input.snapshot());
        if (exactEventTitle != null) ReportMaintenanceContent.promote(input.report().getStructuredContent(),
                input.view(), exactEventTitle, briefingIndex); // Validate before reserving paid work.
        var candidates = input.view().findings().stream()
                .filter(finding -> !input.view().excludedEvidenceFindingIds().contains(finding.getId())).toList();
        var result = generator.generateRecovery(input.report().getRun(), candidates,
                input.report().getGeneratedAt(), recoveryId);
        if (result.status() != ReportStatus.GENERATED) return new RegenerationResult(false, store.read(reportId).snapshot());
        return new RegenerationResult(true,
                store.replace(reportId, expectedFingerprint, result, exactEventTitle, briefingIndex));
    }

    public record Snapshot(long reportId, Long runId, String fingerprint, ReportStatus status,
                           LocalDateTime generatedAt, List<String> eventTitles) {
        public Snapshot { eventTitles = List.copyOf(eventTitles); }
    }

    public record RegenerationResult(boolean replaced, Snapshot snapshot) { }
}
