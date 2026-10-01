package com.example.be.domain.reports.insight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportImportanceTest {
    @Test void missingEvidenceAndUnrelatedFindingCannotBePromoted() {
        assertEquals("unavailable", ReportImportance.grade(null, 3, 3));
        assertEquals("unavailable", ReportImportance.grade(3, null, 3));
        assertEquals("low", ReportImportance.grade(0, 3, 3));
        assertEquals(0, ReportImportance.score(0, 3, 3));
    }

    @Test void unknownUrgencyRenormalizesAvailableAxesAndThresholdsAreStable() {
        assertEquals("high", ReportImportance.grade(3, 2, null));
        assertEquals("medium", ReportImportance.grade(1, 2, null));
        assertEquals("low", ReportImportance.grade(1, 1, null));
        assertEquals("high", ReportImportance.grade(3, 2, 2));
        assertEquals("medium", ReportImportance.grade(2, 1, 2));
    }
}
