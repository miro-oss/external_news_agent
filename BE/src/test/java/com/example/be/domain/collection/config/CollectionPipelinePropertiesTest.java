package com.example.be.domain.collection.config;

import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CollectionPipelinePropertiesTest {

    @Test
    void acceptsDefaults() {
        assertDoesNotThrow(new CollectionPipelineProperties()::afterPropertiesSet);
        assertDoesNotThrow(new AnalysisSelectionProperties()::afterPropertiesSet);
    }

    @Test
    void rejectsNonPositiveLimits() {
        CollectionPipelineProperties collection = new CollectionPipelineProperties();
        collection.setTopicArticleLimit(0);
        CollectionPipelineProperties fulltext = new CollectionPipelineProperties();
        fulltext.setFulltextLimitPerRun(0);
        AnalysisSelectionProperties analysis = new AnalysisSelectionProperties();
        analysis.setIssueLimitPerRun(0);

        assertThrows(IllegalStateException.class, collection::afterPropertiesSet);
        assertThrows(IllegalStateException.class, fulltext::afterPropertiesSet);
        assertThrows(IllegalStateException.class, analysis::afterPropertiesSet);
    }
}
