package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.converter.ArticleHasher;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleVersion;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.ArticleVersionRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollectionResultWriterInvestigationTest {

    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final ArticleVersionRepository versionRepository = mock(ArticleVersionRepository.class);
    private final CollectionRunArticleRepository observationRepository = mock(CollectionRunArticleRepository.class);
    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final CollectionRunItemRepository itemRepository = mock(CollectionRunItemRepository.class);
    private final TopicRepository topicRepository = mock(TopicRepository.class);
    private final SourceRepository sourceRepository = mock(SourceRepository.class);
    private final CollectionRun run = CollectionRun.builder().id(42L).build();
    private final CollectionResultWriter writer = new CollectionResultWriter(
            articleRepository, versionRepository, observationRepository, runRepository,
            itemRepository, topicRepository, sourceRepository);

    private Topic topic;
    private Source source;

    @BeforeEach
    void setUp() {
        topic = topic(7L, List.of("HBM"), List.of("메모리", "DRAM"), List.of("교육", "부동산"));
        source = source(11L, Source.KIND_SEARCH);
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(topicRepository.findById(7L)).thenReturn(Optional.of(topic));
        when(sourceRepository.findById(11L)).thenAnswer(ignored -> Optional.of(source));
        when(articleRepository.save(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {Source.KIND_SEARCH, Source.KIND_FEED})
    void appliesAllKeywordRulesToSearchAndFeedWithoutExcludingStockOrEtfNews(String sourceKind) {
        source = source(11L, sourceKind);
        CollectionRunItem item = addItem(topic, source, true);
        CollectedArticle stock = article("stock", "HBM 메모리 관련주 상승", "투자자 수급 변화");
        CollectedArticle etf = article("etf", "HBM DRAM ETF 자금 유입", "시장 지수 추종 상품");
        CollectedArticle evidence = article("evidence", "제조사 추가 입장", "HBM 메모리 공급 일정 확인");
        CollectedArticle education = article("education", "HBM 메모리 교육 과정 모집", "인재 양성");
        CollectedArticle realEstate = article("real-estate", "HBM DRAM 산업단지 소식", "지역 부동산 분양 안내");
        CollectedArticle missingRequired = article("missing-required", "DRAM 가격 반등", "메모리 공급");
        CollectedArticle missingOptional = article("missing-optional", "HBM 장비 개발", "공정 기술");

        var result = write(stock, etf, evidence, education, realEstate, missingRequired, missingOptional);

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(3, 3), result);
        assertEquals(List.of(stock.title(), etf.title(), evidence.title()), savedArticles(3).stream()
                .map(Article::getTitle).toList());
        for (CollectedArticle rejected : List.of(education, realEstate, missingRequired, missingOptional)) {
            verify(articleRepository, never()).findByUrlHash(ArticleHasher.urlHash(rejected.canonicalUrl()));
        }
        verify(observationRepository, times(3)).save(any(CollectionRunArticle.class));
        verifyNoInteractions(versionRepository, itemRepository);
        assertEquals(RunItemStatus.SUCCESS, item.getStatus());
        assertEquals(9, item.getScannedCount());
        assertEquals(2, item.getNewCount());
        assertEquals(1, item.getUpdatedCount());
    }

    @Test
    void returnsZeroWithoutArticleMutationsWhenEveryCandidateIsFilteredOut() {
        addItem(topic, source, true);

        var result = write(
                article("excluded", "HBM 메모리", "교육 과정"),
                article("unrelated", "지역 행사", "문화 소식"));

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(0, 0), result);
        verifyNoInteractions(articleRepository, versionRepository, observationRepository, itemRepository);
        assertEquals(List.of(), run.getWarnings());
    }

    @Test
    void doesNotOverwriteAnExistingArticleWhenItsNewMetadataMatchesAnExcludedKeyword() {
        addItem(topic, source, true);
        CollectionRun previousRun = CollectionRun.builder().id(41L).build();
        Article existing = Article.builder()
                .id(101L).title("HBM 메모리 공급").summary("기존 요약").body("기존 전문")
                .contentHash("previous-hash").lastSeenRun(previousRun).build();
        CollectedArticle excluded = article("existing", "HBM 메모리", "교육 안내로 변경된 요약");
        when(articleRepository.findByUrlHash(ArticleHasher.urlHash(excluded.canonicalUrl())))
                .thenReturn(Optional.of(existing));

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(0, 0), write(excluded));

        verifyNoInteractions(articleRepository, versionRepository, observationRepository);
        assertEquals("HBM 메모리 공급", existing.getTitle());
        assertEquals("기존 요약", existing.getSummary());
        assertEquals("기존 전문", existing.getBody());
        assertSame(previousRun, existing.getLastSeenRun());
    }

    @Test
    void filtersBeforeUrlDeduplicationSoAnEarlierRejectedVariantDoesNotHideValidEvidence() {
        addItem(topic, source, true);
        CollectedArticle excluded = article("same?utm_source=feed", "HBM 메모리 교육", "과정 안내");
        CollectedArticle accepted = article("same?fbclid=search", "HBM 메모리 공급 확인", "제조사 추가 입장");
        CollectedArticle duplicate = article("same", "HBM DRAM 생산 일정", "동일 URL 재노출");

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(1, 1), write(excluded, accepted, duplicate));

        Article saved = savedArticles(1).getFirst();
        assertEquals(accepted.title(), saved.getTitle());
        assertEquals("https://example.com/same", saved.getCanonicalUrl());
        verify(observationRepository).save(any(CollectionRunArticle.class));
    }

    @Test
    void usesCapturedTopicRulesAfterEditsEvenForASourceAddedAfterRunAcceptance() {
        Topic unrelated = topic(8L, List.of("다른주제"), List.of(), List.of());
        addItem(unrelated, source, true);
        Source originalSource = source(12L, Source.KIND_FEED);
        addItem(topic, originalSource, true);
        topic.update("수정한 주제", "GPU inference", List.of("GPU"), List.of("inference"),
                List.of("ETF"), 10, 60, true);
        CollectedArticle accepted = article("captured", "HBM 메모리 ETF", "접수한 조건의 기사");
        CollectedArticle currentOnly = article("current", "GPU inference", "수정한 조건에서만 일치");
        CollectedArticle originallyExcluded = article("captured-excluded", "HBM DRAM 교육", "접수 당시 제외");

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(1, 1),
                write(accepted, currentOnly, originallyExcluded));

        Article saved = savedArticles(1).getFirst();
        assertEquals(accepted.title(), saved.getTitle());
        assertSame(topic, saved.getTopic());
        assertSame(source, saved.getSource());
        ArgumentCaptor<CollectionRunArticle> observation = ArgumentCaptor.forClass(CollectionRunArticle.class);
        verify(observationRepository).save(observation.capture());
        assertSame(topic, observation.getValue().getTopic());
        assertSame(source, observation.getValue().getSource());
        assertEquals(List.of("GPU"), topic.getRequiredKeywords());
    }

    @Test
    void fallsBackToTheRunItemsTopicForLegacyRunsWithoutACapturedSnapshot() {
        addItem(topic, source, false);
        topic.update("수정한 주제", "GPU", List.of("GPU"), List.of(), List.of("교육"), 10, 60, true);
        CollectedArticle accepted = article("legacy", "GPU 투자", "현재 조건 적용");

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(1, 1), write(
                accepted,
                article("old", "HBM 메모리", "예전 조건"),
                article("excluded", "GPU 교육", "제외 조건")));

        assertEquals(accepted.title(), savedArticles(1).getFirst().getTitle());
    }

    @Test
    void preservesFreeFormInvestigationEvidenceWhenNoKeywordRulesWereConfigured() {
        topic.update("제약 없는 주제", "original query", List.of(), List.of(), List.of(), 10, 60, true);
        addItem(topic, source, true);
        CollectedArticle evidence = article("response", "다른 이해관계자의 입장", "새로 확인한 근거");

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(1, 1), write(evidence));

        assertEquals(evidence.title(), savedArticles(1).getFirst().getTitle());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsTopicsOutsideTheRunInsteadOfFallingBackToUnrelatedOrCurrentRules(boolean hasUnrelatedTopic) {
        if (hasUnrelatedTopic) {
            addItem(topic(8L, List.of(), List.of(), List.of()), source, true);
        }

        assertThrows(IllegalArgumentException.class, () -> write(article("outside", "HBM 메모리", "일치 기사")));

        verifyNoInteractions(articleRepository, versionRepository, observationRepository, itemRepository);
        assertEquals(List.of(), run.getWarnings());
    }

    @Test
    void countsOnlyRetainedNewAndUpdatedArticlesAsChangedWhileStillObservingUnchangedArticles() {
        addItem(topic, source, true);
        CollectedArticle fresh = article("new", "HBM 메모리 신규 공급", "새 기사");
        CollectedArticle updated = article("updated", "HBM DRAM 공급 확대", "변경 요약");
        CollectedArticle unchanged = article("unchanged", "HBM 메모리 가격", "동일 요약");
        Article existingUpdated = Article.builder().id(101L)
                .title("HBM DRAM 공급").summary("기존 요약").body("기존 전문").contentHash("old-hash").build();
        Article existingUnchanged = Article.builder().id(102L)
                .title(unchanged.title()).summary(unchanged.summary())
                .contentHash(ArticleHasher.contentHash(unchanged.title(), unchanged.summary(), null)).build();
        when(articleRepository.findByUrlHash(ArticleHasher.urlHash(updated.canonicalUrl())))
                .thenReturn(Optional.of(existingUpdated));
        when(articleRepository.findByUrlHash(ArticleHasher.urlHash(unchanged.canonicalUrl())))
                .thenReturn(Optional.of(existingUnchanged));

        assertEquals(new CollectionResultWriter.InvestigationWriteResult(3, 2), write(
                fresh, updated, unchanged, article("excluded", "HBM 교육", "메모리 과정")));

        ArgumentCaptor<CollectionRunArticle> observations = ArgumentCaptor.forClass(CollectionRunArticle.class);
        verify(observationRepository, times(3)).save(observations.capture());
        assertEquals(List.of(ChangeType.NEW, ChangeType.UPDATED, ChangeType.UNCHANGED),
                observations.getAllValues().stream().map(CollectionRunArticle::getChangeType).toList());
        verify(versionRepository).save(any(ArticleVersion.class));
        assertEquals(updated.summary(), existingUpdated.getSummary());
        assertSame(run, existingUnchanged.getLastSeenRun());
        verify(articleRepository).save(any(Article.class));
    }

    private CollectionResultWriter.InvestigationWriteResult write(CollectedArticle... articles) {
        CollectionOutcome outcome = CollectionOutcome.of(
                FetchResult.ok(List.of(articles)), RobotsDecision.skipped(source));
        return writer.writeInvestigation(42L, 7L, 11L, outcome);
    }

    private CollectionRunItem addItem(Topic itemTopic, Source itemSource, boolean captureSnapshot) {
        CollectionRunItem item = CollectionRunItem.builder()
                .topic(itemTopic).source(itemSource).status(RunItemStatus.SUCCESS)
                .scannedCount(9).newCount(2).updatedCount(1).build();
        if (captureSnapshot) {
            item.captureTopicSnapshot();
        }
        run.addItem(item);
        return item;
    }

    private List<Article> savedArticles(int count) {
        ArgumentCaptor<Article> articles = ArgumentCaptor.forClass(Article.class);
        verify(articleRepository, times(count)).save(articles.capture());
        return articles.getAllValues();
    }

    private Topic topic(Long id, List<String> required, List<String> optional, List<String> excluded) {
        return Topic.builder().id(id).name("조사 테스트 주제").queryText("original query")
                .requiredKeywords(required).optionalKeywords(optional).excludedKeywords(excluded)
                .batchSize(10).intervalMinutes(60).active(true).build();
    }

    private Source source(Long id, String kind) {
        return Source.builder().id(id).name("조사 테스트 소스").sourceKind(kind).active(true).build();
    }

    private CollectedArticle article(String path, String title, String summary) {
        return new CollectedArticle(title, "https://example.com/" + path, summary, null, "example.com", "ko");
    }
}
