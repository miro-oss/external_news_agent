package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightInputAssemblerTest {

    private AgentProperties properties;
    private NewsIssueRepository issueRepository;
    private IssueArticleRepository issueArticleRepository;
    private FindingRepository findingRepository;
    private InsightInputAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        issueRepository = mock(NewsIssueRepository.class);
        issueArticleRepository = mock(IssueArticleRepository.class);
        findingRepository = mock(FindingRepository.class);
        assembler = new InsightInputAssembler(
                properties,
                issueRepository,
                issueArticleRepository,
                findingRepository,
                new ObjectMapper(),
                new InsightEntityNormalizer());
    }

    @Test
    void assemblesOnlyLlmFindingAndConvertsSentenceIndexToAgentOneBasedId() {
        Topic topic = Topic.builder()
                .id(3L)
                .name("HBM")
                .queryText("HBM 공급")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("공급"))
                .excludedKeywords(List.of("채용"))
                .build();
        NewsIssue issue = NewsIssue.builder().id(88L).topic(topic).build();
        Article article = Article.builder()
                .id(10L)
                .title("HBM4 기사")
                .canonicalUrl("https://example.com/10")
                .publishedAt(OffsetDateTime.parse("2026-09-02T15:30:00Z"))
                .build();
        IssueArticle membership = IssueArticle.builder().issue(issue).article(article).build();
        Finding finding = Finding.builder()
                .id(501L)
                .run(CollectionRun.builder().id(42L).build())
                .article(article)
                .summary("HBM4 일정 요약")
                .analysisSource(AnalysisSource.LLM)
                .entities(new FindingEntities(List.of("SK하이닉스"), List.of("HBM4"), List.of()))
                .sections(List.of(
                        new FindingSection(0, "첫 문장"),
                        new FindingSection(1, "둘째 문장")))
                .build();
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(membership));
        when(findingRepository.findLatestByArticleIds(List.of(10L)))
                .thenReturn(List.of(finding));
        when(findingRepository.findInsightHistoryCandidates(
                eq(3L), any(), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        InsightInputAssembler.Snapshot snapshot = assembler.assemble(88L);

        assertEquals(42L, snapshot.runId());
        assertEquals(64, snapshot.inputHash().length());
        assertEquals(1, snapshot.findings().size());
        assertEquals(AgentInsightRequest.FindingRole.CURRENT, snapshot.findings().getFirst().role());
        assertEquals("2026-09-03", snapshot.findings().getFirst().publishedAt());
        assertEquals(List.of(1, 2), snapshot.findings().getFirst().sentences().stream()
                .map(sentence -> sentence.id()).toList());
        assertEquals(10L, snapshot.articleIdsByFinding().get(501L));
        assertEquals("HBM", snapshot.topic().name());
        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(findingRepository).findInsightHistoryCandidates(
                eq(3L), since.capture(), any(), anyCollection(), any(Pageable.class));
        assertEquals(LocalTime.MIDNIGHT, since.getValue().toLocalTime());
        assertEquals(ZoneOffset.ofHours(9), since.getValue().getOffset());
    }

    @Test
    void appendsHistoryFindingsThatShareTopicAndEntities() {
        Topic topic = Topic.builder()
                .id(3L)
                .name("HBM")
                .queryText("HBM 공급")
                .build();
        NewsIssue issue = NewsIssue.builder().id(88L).topic(topic).build();
        Article currentArticle = Article.builder()
                .id(10L)
                .title("HBM4 기사")
                .canonicalUrl("https://example.com/10")
                .publishedAt(OffsetDateTime.parse("2026-09-03T09:00:00+09:00"))
                .build();
        Article historyArticle = Article.builder()
                .id(11L)
                .title("지난달 HBM 기사")
                .canonicalUrl("https://example.com/11")
                .publishedAt(OffsetDateTime.parse("2026-08-27T09:00:00+09:00"))
                .build();
        Finding current = Finding.builder()
                .id(501L)
                .run(CollectionRun.builder().id(42L).build())
                .article(currentArticle)
                .summary("현재 일정 요약")
                .analysisSource(AnalysisSource.LLM)
                .entities(new FindingEntities(List.of("SK하이닉스"), List.of("HBM4"), List.of()))
                .sections(List.of(new FindingSection(0, "현재 문장")))
                .analyzedAt(java.time.LocalDateTime.of(2026, 9, 3, 9, 30))
                .build();
        Finding history = Finding.builder()
                .id(388L)
                .run(CollectionRun.builder().id(41L).build())
                .article(historyArticle)
                .summary("과거 일정 요약")
                .analysisSource(AnalysisSource.REUSED)
                .entities(new FindingEntities(List.of("SK하이닉스"), List.of("HBM3E"), List.of()))
                .sections(List.of(new FindingSection(0, "3주 전에는 2028년 목표였다.")))
                .analyzedAt(java.time.LocalDateTime.of(2026, 8, 27, 11, 0))
                .build();
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(IssueArticle.builder().issue(issue).article(currentArticle).build()));
        when(findingRepository.findLatestByArticleIds(List.of(10L)))
                .thenReturn(List.of(current));
        when(findingRepository.findInsightHistoryCandidates(
                eq(3L), any(), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(current, history));

        InsightInputAssembler.Snapshot snapshot = assembler.assemble(88L);

        verify(findingRepository).findInsightHistoryCandidates(
                eq(3L), any(), any(), anyCollection(), any(Pageable.class));
        assertEquals(42L, snapshot.runId());
        assertEquals(List.of(501L, 388L), snapshot.findings().stream()
                .map(AgentInsightRequest.FindingPayload::id)
                .toList());
        assertEquals(
                List.of(AgentInsightRequest.FindingRole.CURRENT, AgentInsightRequest.FindingRole.HISTORY),
                snapshot.findings().stream().map(AgentInsightRequest.FindingPayload::role).toList());
        assertEquals("2026-08-27", snapshot.findings().get(1).publishedAt());
        assertEquals(11L, snapshot.articleIdsByFinding().get(388L));
    }

    @Test
    void rejectsIssueWithoutLlmDerivedFinding() {
        Topic topic = Topic.builder().id(3L).name("HBM").build();
        NewsIssue issue = NewsIssue.builder().id(88L).topic(topic).build();
        Article article = Article.builder().id(10L).build();
        Finding stub = Finding.builder()
                .id(501L)
                .article(article)
                .analysisSource(AnalysisSource.STUB)
                .sections(List.of(new FindingSection(0, "문장")))
                .build();
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(IssueArticle.builder().issue(issue).article(article).build()));
        when(findingRepository.findLatestByArticleIds(List.of(10L)))
                .thenReturn(List.of(stub));

        GeneralException exception = assertThrows(
                GeneralException.class,
                () -> assembler.assemble(88L));

        assertEquals("COMMON409", exception.getCode().getCode());
    }
}
