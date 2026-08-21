package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.ArticleVersionRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionResultWriterTest {

    @Test
    void aggregatesRepeatedAgentWarningsIntoOneRunLevelEntry() {
        CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
        CollectionRun run = CollectionRun.builder().id(42L).build();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        CollectionResultWriter writer = new CollectionResultWriter(
                mock(ArticleRepository.class),
                mock(ArticleVersionRepository.class),
                mock(CollectionRunArticleRepository.class),
                runRepository,
                mock(CollectionRunItemRepository.class),
                mock(TopicRepository.class),
                mock(SourceRepository.class));

        writer.addAgentWarning(
                42L, CollectionRunWarning.CODE_LLM_QUOTA_EXHAUSTED, "quota 소진");
        writer.addAgentWarning(
                42L, CollectionRunWarning.CODE_LLM_QUOTA_EXHAUSTED, "quota 소진");

        assertEquals(1, run.getWarnings().size());
        assertEquals(2, run.getWarnings().getFirst().getArticleCount());
    }
}
