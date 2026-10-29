package com.example.be.domain.collection.entity;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectionSourceFingerprintTest {
    @Test void newReservationsFreezeSettingsAndNeverBackfillOldHistoryOnRead() {
        Source source = Source.builder().id(7L).sourceKind("FEED").name("매체")
                .urlTemplate("https://example.com/rss").active(true).build();
        var item = CollectionRunItem.builder().source(source).topic(Topic.builder().id(3L).name("반도체").build()).build();
        assertNull(item.getComparisonSourceFingerprint());
        assertFalse(item.hasComparableSourceConfiguration());
        item.captureTopicSnapshot();
        String original = item.getComparisonSourceFingerprint();
        assertEquals(64, original.length());
        assertTrue(item.hasComparableSourceConfiguration());

        source.applyFetchState("new-etag", "Wed, 28 Oct 2026", java.time.LocalDateTime.now());
        assertTrue(item.hasComparableSourceConfiguration(), "HTTP cache state is not a collection setting");
        source.update("표시명만 변경", "https://example.com/rss", null, null, null, null, true);
        assertTrue(item.hasComparableSourceConfiguration());
        source.update("매체", "https://example.com/other-rss", null, null, null, null, true);
        assertFalse(item.hasComparableSourceConfiguration());
        item.captureTopicSnapshot();
        assertEquals(original, item.getComparisonSourceFingerprint(), "Repeated reservations must not rewrite history");
    }
}
