package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

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
    }

    @Test
    void stripsMarkerWithoutChangingCorePhrase() {
        assertEquals("삼성전자 HBM4 증설", detector.coreTitle("[속보] 삼성전자 HBM4 증설"));
        assertEquals("HBM4 Expansion", detector.coreTitle("Breaking News: HBM4 Expansion"));
    }

    @Test
    void shortBodyRequiresRecentSuccessfulFullText() {
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-31T10:00:00+09:00");

        assertTrue(detector.isRecentShortFullText(
                FetchStatus.FULLTEXT, "짧은 본문", publishedAt, publishedAt.plusHours(2)));
        assertFalse(detector.isRecentShortFullText(
                FetchStatus.METADATA_ONLY, "짧은 본문", publishedAt, publishedAt.plusMinutes(5)));
        assertFalse(detector.isRecentShortFullText(
                FetchStatus.FULLTEXT, "짧은 본문", publishedAt, publishedAt.plusHours(3)));
    }
}
