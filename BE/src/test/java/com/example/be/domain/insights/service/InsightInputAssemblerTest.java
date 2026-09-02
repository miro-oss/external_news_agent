package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsightInputAssemblerTest {

    private NewsIssueRepository issueRepository;
    private IssueArticleRepository issueArticleRepository;
    private FindingRepository findingRepository;
    private InsightInputAssembler assembler;

    @BeforeEach
    void setUp() {
        issueRepository = mock(NewsIssueRepository.class);
        issueArticleRepository = mock(IssueArticleRepository.class);
        findingRepository = mock(FindingRepository.class);
        assembler = new InsightInputAssembler(
                issueRepository,
                issueArticleRepository,
                findingRepository,
                new ObjectMapper());
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
                .build();
        IssueArticle membership = IssueArticle.builder().issue(issue).article(article).build();
        Finding finding = Finding.builder()
                .id(501L)
                .run(CollectionRun.builder().id(42L).build())
                .article(article)
                .summary("HBM4 일정 요약")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(
                        new FindingSection(0, "첫 문장"),
                        new FindingSection(1, "둘째 문장")))
                .build();
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(membership));
        when(findingRepository.findFirstByArticleIdOrderByIdDesc(10L))
                .thenReturn(Optional.of(finding));

        InsightInputAssembler.Snapshot snapshot = assembler.assemble(88L);

        assertEquals(42L, snapshot.runId());
        assertEquals(64, snapshot.inputHash().length());
        assertEquals(List.of(1, 2), snapshot.findings().getFirst().sentences().stream()
                .map(sentence -> sentence.id()).toList());
        assertEquals("HBM", snapshot.topic().name());
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
        when(findingRepository.findFirstByArticleIdOrderByIdDesc(10L))
                .thenReturn(Optional.of(stub));

        GeneralException exception = assertThrows(
                GeneralException.class,
                () -> assembler.assemble(88L));

        assertEquals("COMMON409", exception.getCode().getCode());
    }
}
