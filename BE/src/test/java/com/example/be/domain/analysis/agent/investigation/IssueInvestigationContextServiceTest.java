package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.cluster.BreakingNewsDetector;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueInvestigationContextServiceTest {
    private final IssueArticleRepository memberships = mock(IssueArticleRepository.class);
    private final CollectionRunArticleRepository observations = mock(CollectionRunArticleRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final SourceRepository sources = mock(SourceRepository.class);
    private final BreakingNewsDetector breaking = mock(BreakingNewsDetector.class);
    private final AgentProperties properties = new AgentProperties();
    private final TopicRelevancePolicy relevancePolicy = mock(TopicRelevancePolicy.class);
    private final IssueInvestigationContextService service = new IssueInvestigationContextService(
            memberships, observations, findings, sources, breaking, properties, relevancePolicy);
    private final Topic topic = Topic.builder().id(7L).name("반도체 장비").build();

    @Test
    void removesRejectedRepresentativesBeforeImportanceLimitAndContextLoading() {
        IssueArticle irrelevant = representative(100L, 10L, "토스 공정위 분쟁", 90);
        IssueArticle uncertain = representative(101L, 11L, "판정 보류", 80);
        IssueArticle related = representative(102L, 12L, "식각 장비 증설", 70);
        properties.getInvestigation().setCandidateLimit(1);
        when(relevancePolicy.excludedKeys(42L)).thenReturn(Set.of(
                new TopicRelevanceGate.Key(10L, 7L), new TopicRelevanceGate.Key(11L, 7L)));
        when(memberships.findRepresentativesForRun(42L)).thenReturn(List.of(irrelevant, uncertain, related));
        when(memberships.findByIssueIdOrderByJoinedAtAsc(102L)).thenReturn(List.of(related));

        List<InvestigationContext> candidates = service.candidates(42L);

        assertThat(candidates).extracting(InvestigationContext::issueId).containsExactly(102L);
        verify(memberships, never()).findByIssueIdOrderByJoinedAtAsc(100L);
        verify(memberships, never()).findByIssueIdOrderByJoinedAtAsc(101L);
    }

    @Test
    void removesKnownNegativeMembersFromCurrentContextOnlyForTheSameTopic() {
        IssueArticle representative = representative(100L, 10L, "식각 장비 증설", 90);
        IssueArticle irrelevant = member(11L, "토스 분쟁", representative.getIssue());
        IssueArticle shared = member(12L, "증착 장비 투자", representative.getIssue());
        when(relevancePolicy.excludedKeys(42L)).thenReturn(Set.of(
                new TopicRelevanceGate.Key(11L, 7L), new TopicRelevanceGate.Key(12L, 8L)));
        when(memberships.findByIssueIdOrderByJoinedAtAsc(100L))
                .thenReturn(List.of(representative, irrelevant, shared));

        InvestigationContext context = service.current(42L, 100L);

        assertThat(context.articleIds()).containsExactly(10L, 12L);
        verify(findings).findByRunIdAndArticleIdIn(42L, List.of(10L, 12L));
    }

    @Test
    void keepsUnassessedMetadataCandidateEligibleForFullTextInvestigation() {
        IssueArticle representative = representative(100L, 10L, "식각 장비 증설", 90);
        Article metadata = Article.builder().id(10L).topic(topic).title("식각 장비 증설")
                .fetchStatus(FetchStatus.METADATA_ONLY).build();
        representative = IssueArticle.builder().issue(representative.getIssue()).article(metadata)
                .role(IssueArticleRole.REPRESENTATIVE).build();
        when(relevancePolicy.excludedKeys(42L)).thenReturn(Set.of());
        when(observations.findArticleIdsByRunId(42L)).thenReturn(List.of(10L));
        when(memberships.findRepresentativesForRun(42L)).thenReturn(List.of(representative));
        when(memberships.findByIssueIdOrderByJoinedAtAsc(100L)).thenReturn(List.of(representative));

        List<InvestigationContext> candidates = service.candidates(42L);

        assertThat(candidates).singleElement().satisfies(context -> {
            assertThat(context.metadataOnlyArticleIds()).containsExactly(10L);
            assertThat(context.articleIds()).containsExactly(10L);
        });
    }

    private IssueArticle representative(Long issueId, Long articleId, String title, int importance) {
        NewsIssue issue = NewsIssue.builder().id(issueId).topic(topic).title(title)
                .status(IssueStatus.EMERGING).articleCount(1)
                .importanceScore(BigDecimal.valueOf(importance)).firstSeenAt(OffsetDateTime.now()).build();
        return IssueArticle.builder().issue(issue).article(article(articleId, title))
                .role(IssueArticleRole.REPRESENTATIVE).build();
    }

    private IssueArticle member(Long articleId, String title, NewsIssue issue) {
        return IssueArticle.builder().issue(issue).article(article(articleId, title))
                .role(IssueArticleRole.MEMBER).build();
    }

    private Article article(Long id, String title) {
        return Article.builder().id(id).topic(topic).title(title).language("ko")
                .body("확보한 기사 전문입니다.").fetchStatus(FetchStatus.FULLTEXT).build();
    }
}
