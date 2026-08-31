package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakingNewsDetectorTest {

    private final BreakingNewsDetector detector = new BreakingNewsDetector();

    @Test
    void detectsOnlyExplicitBreakingMarkers() {
        assertTrue(detector.hasExplicitMarker("[속보] HBM4 증설"));
        assertTrue(detector.hasExplicitMarker("[1보] HBM4 증설"));
        assertTrue(detector.hasExplicitMarker("BREAKING NEWS: HBM4 expansion"));
        assertTrue(detector.hasExplicitMarker("Urgent - HBM4 expansion"));
        assertFalse(detector.hasExplicitMarker("[단독] HBM4 증설"));
        assertFalse(detector.hasExplicitMarker("Company Sets Record-Breaking Revenue"));
        assertFalse(detector.hasExplicitMarker("Company Is Breaking Ground on New Plant"));
        assertFalse(detector.hasExplicitMarker("Manufacturer Issues Urgent Recall"));
    }

    @Test
    void stripsMarkerWithoutChangingCorePhrase() {
        assertEquals("삼성전자 HBM4 증설", detector.coreTitle("[속보] 삼성전자 HBM4 증설"));
        assertEquals("HBM4 Expansion", detector.coreTitle("Breaking News: HBM4 Expansion"));
    }

}
