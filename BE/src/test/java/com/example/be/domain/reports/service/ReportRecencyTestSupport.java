package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;

import static org.mockito.Mockito.mock;

final class ReportRecencyTestSupport {
    private ReportRecencyTestSupport() { }

    static ReportRecencyPolicy policy() {
        return new ReportRecencyPolicy(mock(CollectionRunItemRepository.class),
                new CollectionPipelineProperties(), TopicRelevanceTestSupport.legacyPolicy());
    }
}
