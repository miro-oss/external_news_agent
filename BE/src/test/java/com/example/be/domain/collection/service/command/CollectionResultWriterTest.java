package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleBody;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.FetchStatus;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollectionResultWriterTest {

    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final ArticleBodyStorage bodyStorage = mock(ArticleBodyStorage.class);

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
                mock(SourceRepository.class),
                mock(ArticleBodyStorage.class));

        writer.addAgentWarning(
                42L, CollectionRunWarning.CODE_LLM_QUOTA_EXHAUSTED, "quota 소진");
        writer.addAgentWarning(
                42L, CollectionRunWarning.CODE_LLM_QUOTA_EXHAUSTED, "quota 소진");

        assertEquals(1, run.getWarnings().size());
        assertEquals(2, run.getWarnings().getFirst().getArticleCount());
    }

    @Test
    void sharesStoredFullTextAcrossDifferentArticleUrlsWithoutChangingTheirMetadataHashes() {
        Article first = Article.builder().id(1L).canonicalUrl("https://first.example/news")
                .contentHash("first-metadata").fetchStatus(FetchStatus.METADATA_ONLY).build();
        Article second = Article.builder().id(2L).canonicalUrl("https://second.example/news")
                .contentHash("second-metadata").fetchStatus(FetchStatus.METADATA_ONLY).build();
        ArticleBody shared = ArticleBody.of("동일한 전재 기사 전문");
        when(articleRepository.findById(1L)).thenReturn(Optional.of(first));
        when(articleRepository.findById(2L)).thenReturn(Optional.of(second));
        when(bodyStorage.intern(shared.getBody())).thenReturn(shared);
        CollectionResultWriter writer = writer();

        writer.applyFullText(1L, FetchStatus.FULLTEXT, shared.getBody());
        writer.applyFullText(2L, FetchStatus.FULLTEXT, shared.getBody());

        assertSame(shared, first.getStoredBody());
        assertSame(shared, second.getStoredBody());
        assertEquals("first-metadata", first.getContentHash());
        assertEquals("second-metadata", second.getContentHash());
    }

    @Test
    void failedFetchKeepsPreviousFullTextWithoutTryingToStoreTheFailureBody() {
        ArticleBody original = ArticleBody.of("이전 전문");
        Article article = Article.builder().id(1L).storedBody(original).fetchStatus(FetchStatus.FULLTEXT).build();
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));

        writer().applyFullText(1L, FetchStatus.FETCH_FAILED, "실패한 페이지 내용");

        assertSame(original, article.getStoredBody());
        assertEquals(FetchStatus.FETCH_FAILED, article.getFetchStatus());
        verifyNoInteractions(bodyStorage);
    }

    @Test
    void storageFailureLeavesThePreviousArticleStateUntouched() {
        ArticleBody original = ArticleBody.of("이전 전문");
        Article article = Article.builder().id(1L).storedBody(original).fetchStatus(FetchStatus.METADATA_ONLY).build();
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));
        when(bodyStorage.intern("정정 전문")).thenThrow(new IllegalStateException("본문 저장 실패"));

        assertThrows(IllegalStateException.class, () -> writer().applyFullText(1L, FetchStatus.FULLTEXT, "정정 전문"));

        assertSame(original, article.getStoredBody());
        assertEquals(FetchStatus.METADATA_ONLY, article.getFetchStatus());
    }

    private CollectionResultWriter writer() {
        return new CollectionResultWriter(articleRepository, mock(ArticleVersionRepository.class),
                mock(CollectionRunArticleRepository.class), mock(CollectionRunRepository.class),
                mock(CollectionRunItemRepository.class), mock(TopicRepository.class),
                mock(SourceRepository.class), bodyStorage);
    }
}
