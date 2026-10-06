package com.example.be.domain.reports.insight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportImportanceTest {
    @Test void confirmedUnrelatedFindingIsLowEvenWhenImpactOrUrgencyIsUnknown() {
        for (Integer impact : new Integer[] { null, 0, 1, 2, 3 }) {
            for (Integer urgency : new Integer[] { null, 0, 1, 2, 3 }) {
                assertEquals("low", ReportImportance.grade(0, impact, urgency));
                assertEquals(0, ReportImportance.score(0, impact, urgency));
            }
        }
    }

    @Test void unknownRelevanceRemainsUnavailableForEveryImpactAndUrgency() {
        for (Integer impact : new Integer[] { null, 0, 1, 2, 3 }) {
            for (Integer urgency : new Integer[] { null, 0, 1, 2, 3 }) {
                assertEquals("unavailable", ReportImportance.grade(null, impact, urgency));
                assertEquals(-1, ReportImportance.score(null, impact, urgency));
            }
        }
    }

    @Test void relevantFindingStillNeedsImpactEvidence() {
        for (int directness = 1; directness <= 3; directness++) {
            for (Integer urgency : new Integer[] { null, 0, 1, 2, 3 }) {
                assertEquals("unavailable", ReportImportance.grade(directness, null, urgency));
                assertEquals(-1, ReportImportance.score(directness, null, urgency));
            }
        }
    }

    @Test void unknownUrgencyRenormalizesAvailableAxesAndThresholdsAreStable() {
        assertEquals("high", ReportImportance.grade(3, 2, null));
        assertEquals("medium", ReportImportance.grade(1, 2, null));
        assertEquals("low", ReportImportance.grade(1, 1, null));
        assertEquals("high", ReportImportance.grade(3, 2, 2));
        assertEquals("medium", ReportImportance.grade(2, 1, 2));
    }
}
