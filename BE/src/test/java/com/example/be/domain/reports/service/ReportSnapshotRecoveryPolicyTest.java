package com.example.be.domain.reports.service;

import com.example.be.domain.reports.keyword.ReportKeywordSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSnapshotRecoveryPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_SCOPE_UNVERIFIED", "PERSONALIZATION_UNVERIFIED"})
    void recoveryNeverClearsAnUnverifiedPolicyGuard(String reason) {
        assertFalse(ReportSnapshotRecoveryService.needsKeywordRecovery(snapshot(reason)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SNAPSHOT", "INCOMPLETE_SOURCE", "MISSING_COLLECTION_CONTEXT", "INVALID_SNAPSHOT"})
    void missingHistoricalDataCanBeRecovered(String reason) {
        assertTrue(ReportSnapshotRecoveryService.needsKeywordRecovery(snapshot(reason)));
    }

    @Test void missingSnapshotIsRecoverableAndAvailableSnapshotIsPreserved() {
        assertTrue(ReportSnapshotRecoveryService.needsKeywordRecovery(null));
        assertFalse(ReportSnapshotRecoveryService.needsKeywordRecovery(snapshot(null)));
    }

    private ReportKeywordSnapshot snapshot(String reason) {
        return new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION, OffsetDateTime.now(),
                "수집 기준", reason, List.of(), List.of());
    }
}
