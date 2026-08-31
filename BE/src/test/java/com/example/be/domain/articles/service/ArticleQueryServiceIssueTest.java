package com.example.be.domain.articles.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.articles.dto.res.ArticleResDTO;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleQueryServiceIssueTest {

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final ArticleQueryServiceImpl service = new ArticleQueryServiceImpl(
            findingRepository, articleRepository, issueArticleRepository);

    @Test
    void memberDetailUsesRepresentativeAnalysisAndLinksIssue() {
        Topic topic = Topic.builder().id(7L).name("HBM").build();
        Source source = Source.builder().id(9L).name("전자신문").build();
        Article representative = article(101L, "대표 기사", "대표 본문", topic, source);
        Article member = article(102L, "전재 기사", "멤버 본문", topic, source);
        NewsIssue issue = NewsIssue.builder()
                .id(88L)
                .title("HBM4 양산")
                .status(IssueStatus.EMERGING)
                .topic(topic)
                .build();
        IssueArticle representativeMembership = membership(
                1L, issue, representative, IssueArticleRole.REPRESENTATIVE);
        IssueArticle memberMembership = membership(2L, issue, member, IssueArticleRole.MEMBER);
        Finding finding = finding(representative);

        when(articleRepository.findById(102L)).thenReturn(Optional.of(member));
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(102L))
                .thenReturn(List.of(memberMembership));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(representativeMembership, memberMembership));
        when(findingRepository.findFirstByArticleIdOrderByIdDesc(101L))
                .thenReturn(Optional.of(finding));

        ArticleResDTO.Detail detail = service.getArticle(102L, null);

        assertEquals(88L, detail.getIssueId());
        assertEquals(101L, detail.getAnalysisArticleId());
        assertEquals("대표 분석", detail.getAnalysis().getSummary());
        assertEquals("대표 근거 문장", detail.getBodyText());
        assertEquals(List.of(101L), detail.getRelatedArticles().stream()
                .map(ArticleResDTO.RelatedArticle::getId)
                .toList());
    }

    @Test
    void ignoresMembershipFromAnotherTopic() {
        Topic articleTopic = Topic.builder().id(7L).name("HBM").build();
        Topic otherTopic = Topic.builder().id(8L).name("제조").build();
        Source source = Source.builder().id(9L).name("전자신문").build();
        Article article = article(102L, "기사", "기사 본문", articleTopic, source);
        NewsIssue otherIssue = NewsIssue.builder()
                .id(99L)
                .title("다른 주제 이슈")
                .status(IssueStatus.EMERGING)
                .topic(otherTopic)
                .build();

        when(articleRepository.findById(102L)).thenReturn(Optional.of(article));
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(102L))
                .thenReturn(List.of(membership(3L, otherIssue, article, IssueArticleRole.REPRESENTATIVE)));
        when(findingRepository.findFirstByArticleIdOrderByIdDesc(102L)).thenReturn(Optional.empty());

        ArticleResDTO.Detail detail = service.getArticle(102L, null);

        assertNull(detail.getIssueId());
        assertEquals(102L, detail.getAnalysisArticleId());
        assertEquals(List.of(), detail.getRelatedArticles());
    }

    @Test
    void promotedMemberDetailUsesItsOwnAnalysisAndEvidence() {
        Topic topic = Topic.builder().id(7L).name("HBM").build();
        Source source = Source.builder().id(9L).name("전자신문").build();
        Article representative = article(101L, "대표 기사", "대표 본문", topic, source);
        Article promoted = article(102L, "충돌 기사", "충돌 본문", topic, source);
        NewsIssue issue = NewsIssue.builder()
                .id(88L)
                .title("HBM4 양산")
                .status(IssueStatus.EMERGING)
                .topic(topic)
                .build();
        IssueArticle representativeMembership = membership(
                1L, issue, representative, IssueArticleRole.REPRESENTATIVE);
        IssueArticle promotedMembership = membership(
                2L, issue, promoted, IssueArticleRole.MEMBER, IssueStanceSource.LLM);
        Finding finding = Finding.builder()
                .id(502L)
                .run(CollectionRun.builder().id(42L).build())
                .article(promoted)
                .changeType(ChangeType.UPDATED)
                .summary("충돌 기사 분석")
                .keyPoints(List.of(new FindingKeyPoint("충돌 주장", List.of(0), "grounded")))
                .sentiment(Sentiment.NEGATIVE)
                .riskLevel(RiskLevel.HIGH)
                .relevance(Relevance.IMPORTANT)
                .category("기업")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "충돌 근거 문장")))
                .analyzedAt(LocalDateTime.of(2026, 8, 10, 10, 2))
                .build();

        when(articleRepository.findById(102L)).thenReturn(Optional.of(promoted));
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(102L))
                .thenReturn(List.of(promotedMembership));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(representativeMembership, promotedMembership));
        when(findingRepository.findFirstByArticleIdOrderByIdDesc(102L))
                .thenReturn(Optional.of(finding));

        ArticleResDTO.Detail detail = service.getArticle(102L, null);

        assertEquals(102L, detail.getAnalysisArticleId());
        assertEquals("충돌 기사 분석", detail.getAnalysis().getSummary());
        assertEquals("충돌 근거 문장", detail.getBodyText());
    }

    @Test
    void promotedMemberFallsBackToRepresentativeFindingForRequestedRun() {
        Topic topic = Topic.builder().id(7L).name("HBM").build();
        Source source = Source.builder().id(9L).name("전자신문").build();
        Article representative = article(101L, "대표 기사", "대표 본문", topic, source);
        Article promoted = article(102L, "충돌 기사", "충돌 본문", topic, source);
        NewsIssue issue = NewsIssue.builder()
                .id(88L)
                .title("HBM4 양산")
                .status(IssueStatus.EMERGING)
                .topic(topic)
                .build();
        IssueArticle representativeMembership = membership(
                1L, issue, representative, IssueArticleRole.REPRESENTATIVE);
        IssueArticle promotedMembership = membership(
                2L, issue, promoted, IssueArticleRole.MEMBER, IssueStanceSource.LLM);
        Finding representativeFinding = Finding.builder()
                .id(503L)
                .run(CollectionRun.builder().id(50L).build())
                .article(representative)
                .changeType(ChangeType.UPDATED)
                .summary("대표 기사 분석")
                .keyPoints(List.of(new FindingKeyPoint("대표 주장", List.of(0), "grounded")))
                .sentiment(Sentiment.NEUTRAL)
                .riskLevel(RiskLevel.MEDIUM)
                .relevance(Relevance.IMPORTANT)
                .category("기업")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "대표 근거 문장")))
                .analyzedAt(LocalDateTime.of(2026, 8, 10, 10, 2))
                .build();

        when(articleRepository.findById(102L)).thenReturn(Optional.of(promoted));
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(102L))
                .thenReturn(List.of(promotedMembership));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(representativeMembership, promotedMembership));
        when(findingRepository.findByRunIdAndArticleId(50L, 102L))
                .thenReturn(Optional.empty());
        when(findingRepository.findByRunIdAndArticleId(50L, 101L))
                .thenReturn(Optional.of(representativeFinding));

        ArticleResDTO.Detail detail = service.getArticle(102L, 50L);

        assertEquals(101L, detail.getAnalysisArticleId());
        assertEquals("대표 기사 분석", detail.getAnalysis().getSummary());
        assertEquals("대표 근거 문장", detail.getBodyText());
    }

    private Article article(Long id, String title, String body, Topic topic, Source source) {
        return Article.builder()
                .id(id)
                .title(title)
                .body(body)
                .topic(topic)
                .source(source)
                .sourceName(source.getName())
                .fetchStatus(FetchStatus.FULLTEXT)
                .collectedAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .build();
    }

    private IssueArticle membership(Long id,
                                    NewsIssue issue,
                                    Article article,
                                    IssueArticleRole role) {
        return membership(id, issue, article, role, IssueStanceSource.RULE);
    }

    private IssueArticle membership(Long id,
                                    NewsIssue issue,
                                    Article article,
                                    IssueArticleRole role,
                                    IssueStanceSource stanceSource) {
        return IssueArticle.builder()
                .id(id)
                .issue(issue)
                .article(article)
                .role(role)
                .stance(IssueStance.SUPPORTS)
                .stanceSource(stanceSource)
                .stanceConfidence(BigDecimal.ONE)
                .joinedAt(LocalDateTime.of(2026, 8, 10, 10, 1))
                .build();
    }

    private Finding finding(Article representative) {
        return Finding.builder()
                .id(501L)
                .run(CollectionRun.builder().id(42L).build())
                .article(representative)
                .changeType(ChangeType.NEW)
                .summary("대표 분석")
                .keyPoints(List.of(new FindingKeyPoint("대표 주장", List.of(0), "grounded")))
                .sentiment(Sentiment.NEUTRAL)
                .riskLevel(RiskLevel.MEDIUM)
                .relevance(Relevance.IMPORTANT)
                .category("기업")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "대표 근거 문장")))
                .analyzedAt(LocalDateTime.of(2026, 8, 10, 10, 2))
                .build();
    }
}
