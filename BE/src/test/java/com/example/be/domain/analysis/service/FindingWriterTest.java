package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.AudienceRelevance;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingAnalysisBullet;
import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingPerspectiveTag;
import com.example.be.domain.analysis.entity.FindingSensitivityAxis;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.cluster.BreakingNewsDetector;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
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
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final NewsWatchRepository watchRepository = mock(NewsWatchRepository.class);
    private final SensitivityCalculator sensitivityCalculator = SensitivityCalculator.defaults();
    private final BreakingNewsDetector breakingNewsDetector = new BreakingNewsDetector();
    private final FindingWriter writer = new FindingWriter(
            findingRepository, runRepository, articleRepository, issueArticleRepository,
            watchRepository, sensitivityCalculator, breakingNewsDetector);

    @Test
    void locksArticleBeforeCheckingForDuplicateFinding() {
        CollectionRun run = mock(CollectionRun.class);
        Article article = mock(Article.class);
        AnalysisResult result = mock(AnalysisResult.class);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(true);

        writer.write(42L, 10L, ChangeType.UPDATED, "a".repeat(64), result);

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
        List<FindingAnalysisSection> analysisSections = List.of(new FindingAnalysisSection(
                "핵심",
                List.of(new FindingAnalysisBullet(
                        "핵심 주장", List.of(0), "grounded", BigDecimal.ONE))));
        AnalysisResult result = new AnalysisResult(
                "한국어 요약",
                List.of(new FindingKeyPoint("핵심 주장", List.of(0), "grounded")),
                "산업 동향 보도",
                Sentiment.NEUTRAL,
                com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.LOW),
                Relevance.REFERENCE, "기업", List.of(),
                AnalysisSource.LLM, analysisSections, FindingEntities.empty(),
                List.of(new FindingPerspectiveTag(
                        Audience.CHIP_MAKER,
                        AudienceRelevance.HIGH,
                        "핵심 주장",
                        List.of(0))),
                metadata);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(false);

        writer.write(42L, 10L, ChangeType.NEW, "a".repeat(64), result);

        ArgumentCaptor<Finding> finding = ArgumentCaptor.forClass(Finding.class);
        verify(findingRepository).save(finding.capture());
        assertEquals(AnalysisSource.LLM, finding.getValue().getAnalysisSource());
        assertEquals("gemini", finding.getValue().getLlmProvider());
        assertEquals("a".repeat(64), finding.getValue().getAnalysisInputHash());
        assertTrue(finding.getValue().isInputTruncated());
        assertTrue(finding.getValue().getKeyPoints().isEmpty());
        assertEquals("핵심 주장", finding.getValue().getEffectiveKeyPoints().getFirst().text());
        assertEquals(Audience.CHIP_MAKER,
                finding.getValue().getPerspectiveTags().getFirst().audience());
    }

    @Test
    void appliesRepresentativeSummaryToEveryLinkedIssue() {
        CollectionRun run = mock(CollectionRun.class);
        Article article = mock(Article.class);
        NewsIssue firstIssue = NewsIssue.builder().id(1L).build();
        NewsIssue secondIssue = NewsIssue.builder().id(2L).build();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(false);
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(10L)).thenReturn(List.of(
                IssueArticle.builder()
                        .issue(firstIssue)
                        .article(article)
                        .role(IssueArticleRole.REPRESENTATIVE)
                        .build(),
                IssueArticle.builder()
                        .issue(secondIssue)
                        .article(article)
                        .role(IssueArticleRole.REPRESENTATIVE)
                        .build()));
        AnalysisResult result = new AnalysisResult(
                "대표 이슈 요약", List.of(), null, Sentiment.NEUTRAL,
                com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.LOW),
                Relevance.REFERENCE, "기업", List.of());

        writer.write(42L, 10L, ChangeType.NEW, "a".repeat(64), result);

        assertEquals("대표 이슈 요약", firstIssue.getSummary());
        assertEquals("대표 이슈 요약", secondIssue.getSummary());
    }

    @Test
    void breakingMemberDoesNotOverwriteRepresentativeSummary() {
        CollectionRun run = mock(CollectionRun.class);
        Article article = mock(Article.class);
        NewsIssue issue = NewsIssue.builder().id(1L).summary("대표 기사 요약").build();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(article.getTitle()).thenReturn("[속보] 후속 기사");
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(false);
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(10L)).thenReturn(List.of(
                IssueArticle.builder().issue(issue).article(article)
                        .role(IssueArticleRole.BREAKING).build()));
        AnalysisResult result = new AnalysisResult(
                "후속 기사 요약", List.of(), null, Sentiment.NEUTRAL,
                com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.LOW),
                Relevance.REFERENCE, "기업", List.of());

        writer.write(42L, 10L, ChangeType.NEW, "a".repeat(64), result);

        assertEquals("대표 기사 요약", issue.getSummary());
    }

    @Test
    void registersHighSensitivityWatchForHighScoringBreakingArticle() {
        CollectionRun run = mock(CollectionRun.class);
        Article article = mock(Article.class);
        NewsIssue issue = NewsIssue.builder().id(88L).build();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(article.getTitle()).thenReturn("[속보] 고민감도 기사");
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(false);
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(10L)).thenReturn(List.of(
                IssueArticle.builder()
                        .issue(issue)
                        .article(article)
                        .role(IssueArticleRole.BREAKING)
                        .build()));
        when(watchRepository.findByIssueIdAndWatchType(88L, WatchType.HIGH_SENSITIVITY))
                .thenReturn(Optional.empty());
        AnalysisResult result = new AnalysisResult(
                "고민감도 속보 요약", List.of(), null, Sentiment.NEUTRAL,
                sensitivityCalculator.calculate(
                        new FindingSensitivityAxis(3, List.of(0)),
                        FindingSensitivityAxis.unavailable(),
                        new FindingSensitivityAxis(2, List.of(0)),
                        new FindingSensitivityAxis(1, List.of(0))),
                Relevance.IMPORTANT, "기업", List.of());

        writer.write(42L, 10L, ChangeType.NEW, "a".repeat(64), result);

        ArgumentCaptor<NewsWatch> watch = ArgumentCaptor.forClass(NewsWatch.class);
        verify(watchRepository).save(watch.capture());
        assertEquals(WatchType.HIGH_SENSITIVITY, watch.getValue().getWatchType());
        assertEquals(new BigDecimal("76.19"), watch.getValue().getSensitivityAtWatch());
        assertEquals(null, issue.getSensitivityScore());
    }

    @Test
    void keepsCooldownWhenExtendingActiveHighSensitivityWatch() {
        CollectionRun run = mock(CollectionRun.class);
        Article article = mock(Article.class);
        NewsIssue issue = NewsIssue.builder().id(88L).build();
        java.time.LocalDateTime cooldown = java.time.LocalDateTime.now().plusMinutes(20);
        NewsWatch watch = NewsWatch.builder()
                .watchType(WatchType.HIGH_SENSITIVITY)
                .issue(issue)
                .sensitivityAtWatch(new BigDecimal("75.00"))
                .cooldownUntil(cooldown)
                .expiresAt(java.time.LocalDateTime.now().plusHours(12))
                .active(true)
                .build();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(articleRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(article));
        when(article.getTitle()).thenReturn("[속보] 고민감도 기사");
        when(findingRepository.existsByRunIdAndArticleId(42L, 10L)).thenReturn(false);
        when(issueArticleRepository.findByArticleIdOrderByIssueIdAsc(10L)).thenReturn(List.of(
                IssueArticle.builder().issue(issue).article(article)
                        .role(IssueArticleRole.BREAKING).build()));
        when(watchRepository.findByIssueIdAndWatchType(88L, WatchType.HIGH_SENSITIVITY))
                .thenReturn(Optional.of(watch));
        AnalysisResult result = new AnalysisResult(
                "고민감도 속보 요약", List.of(), null, Sentiment.NEUTRAL,
                sensitivityCalculator.calculate(
                        new FindingSensitivityAxis(3, List.of(0)),
                        FindingSensitivityAxis.unavailable(),
                        new FindingSensitivityAxis(2, List.of(0)),
                        new FindingSensitivityAxis(1, List.of(0))),
                Relevance.IMPORTANT, "기업", List.of());

        writer.write(42L, 10L, ChangeType.NEW, "a".repeat(64), result);

        assertEquals(cooldown, watch.getCooldownUntil());
        assertEquals(new BigDecimal("76.19"), watch.getSensitivityAtWatch());
    }
}
