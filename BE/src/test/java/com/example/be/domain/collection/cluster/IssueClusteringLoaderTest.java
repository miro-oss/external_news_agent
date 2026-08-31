package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IssueClusteringLoaderTest {

    private final CollectionRunArticleRepository observationRepository =
            mock(CollectionRunArticleRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueClusteringProperties properties = new IssueClusteringProperties();
    private final IssueClusteringLoader loader = new IssueClusteringLoader(
            observationRepository, issueArticleRepository, properties);

    @Test
    void combinesCurrentObservationWithRecentIssueMembership() {
        Topic topic = Topic.builder().id(7L).name("HBM").requiredKeywords(List.of("HBM4")).build();
        Source source = Source.builder().id(9L).name("전자신문").build();
        Article representative = article(101L, topic, source, "대표");
        Article current = article(102L, topic, source, "신규");
        NewsIssue issue = NewsIssue.builder().id(88L).topic(topic).build();
        IssueArticle representativeMembership = IssueArticle.builder()
                .issue(issue).article(representative).role(IssueArticleRole.REPRESENTATIVE).build();
        IssueArticle currentMembership = IssueArticle.builder()
                .issue(issue).article(current).role(IssueArticleRole.MEMBER).build();
        CollectionRunArticle observation = CollectionRunArticle.builder()
                .article(current)
                .topic(topic)
                .observedAt(LocalDateTime.of(2026, 8, 10, 12, 0))
                .build();

        when(observationRepository.findClusterTargetsByRunId(42L)).thenReturn(List.of(observation));
        when(issueArticleRepository.findByArticleIds(java.util.Set.of(102L)))
                .thenReturn(List.of(currentMembership));
        when(issueArticleRepository.findRecentByTopicIds(eq(java.util.Set.of(7L)), any()))
                .thenReturn(List.of(representativeMembership));

        List<ClusterArticle> loaded = loader.load(42L);

        assertEquals(List.of(101L, 102L), loaded.stream().map(ClusterArticle::articleId).toList());
        assertFalse(loaded.getFirst().observedInRun());
        assertTrue(loaded.getLast().observedInRun());
        assertEquals(88L, loaded.getLast().existingIssueId());
        assertEquals(OffsetDateTime.parse("2026-08-10T10:00:00+09:00"),
                loaded.getLast().observedAt());
    }

    private Article article(Long id, Topic topic, Source source, String title) {
        return Article.builder()
                .id(id)
                .topic(topic)
                .source(source)
                .title(title)
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .publishedAt(OffsetDateTime.parse("2026-08-10T09:00:00+09:00"))
                .collectedAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .build();
    }
}
