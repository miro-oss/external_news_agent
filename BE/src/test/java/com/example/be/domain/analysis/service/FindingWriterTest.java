package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FindingWriterTest {

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final FindingWriter writer = new FindingWriter(findingRepository, runRepository, articleRepository);

    @Test
    void locksArticleBeforeCheckingForDuplicateFinding() {
        CollectionRun run = mock(CollectionRun.class);
        Article article = mock(Article.class);
        AnalysisResult result = mock(AnalysisResult.class);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(true);

        writer.write(42L, 10L, ChangeType.UPDATED, result);

        InOrder order = inOrder(articleRepository, findingRepository);
        order.verify(articleRepository).findByIdForUpdate(10L);
        order.verify(findingRepository).existsByRunIdAndArticleId(42L, 10L);
        verify(findingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void storesAnalysisSourceAndLlmMetadataOnFinding() {
        CollectionRun run = mock(CollectionRun.class);
        Article article = mock(Article.class);
        AnalysisMetadata metadata = new AnalysisMetadata(
                "analyze.ko.v1", "gemini", "gemini-2.5-flash",
                120L, 30L, new BigDecimal("0.001"), BigDecimal.ZERO, true);
        AnalysisResult result = new AnalysisResult(
                "한국어 요약", List.of(), "산업 동향 보도",
                Sentiment.NEUTRAL, RiskLevel.LOW, Relevance.REFERENCE, "기업", List.of(),
                AnalysisSource.LLM, List.of(), FindingEntities.empty(), metadata);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(false);

        writer.write(42L, 10L, ChangeType.NEW, result);

        ArgumentCaptor<Finding> finding = ArgumentCaptor.forClass(Finding.class);
        verify(findingRepository).save(finding.capture());
        assertEquals(AnalysisSource.LLM, finding.getValue().getAnalysisSource());
        assertEquals("gemini", finding.getValue().getLlmProvider());
        assertTrue(finding.getValue().isInputTruncated());
    }
}
