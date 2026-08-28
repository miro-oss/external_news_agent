package com.example.be.domain.collection.connector.dto.req;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchQueryTest {

    @Test
    void acceptsMaximumBatchSize() {
        SearchQuery query = new SearchQuery("HBM", 300, "ko");

        assertEquals(300, query.batchSize());
    }

    @Test
    void rejectsBatchSizeAboveMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery("HBM", 301, "ko"));
    }
}
