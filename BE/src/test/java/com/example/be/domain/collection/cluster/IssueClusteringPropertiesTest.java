package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IssueClusteringPropertiesTest {

    @Test
    void acceptsSafeDefaults() {
        assertDoesNotThrow(new IssueClusteringProperties()::afterPropertiesSet);
    }

    @Test
    void rejectsOutOfRangeJaccardThreshold() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(1.1);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }
}
