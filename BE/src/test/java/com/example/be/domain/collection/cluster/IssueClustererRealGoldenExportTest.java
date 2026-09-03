package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererRealGoldenExportTest {

    @Test
    void exportsFixtureFeaturesForRatioAndThresholdSweep() throws IOException {
        export(load(), Map.of(), "fixture-fragment",
                Path.of("build/reports/clusters/pairs.v2.json"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CLUSTERS_V2_REPLAY_ARTICLES", matches = ".+")
    void exportsFullBodyReplayFeaturesForRatioAndThresholdSweep() throws IOException {
        RealGoldenDataset dataset = load();
        String replayPath = System.getenv("CLUSTERS_V2_REPLAY_ARTICLES");
        String configuredOutput = System.getenv("CLUSTERS_V2_REPLAY_OUTPUT");
        Path outputPath = Path.of(configuredOutput == null || configuredOutput.isBlank()
                ? "build/reports/clusters/pairs.v2.fullbody.json"
                : configuredOutput);
        export(dataset, loadReplayArticles(dataset, Path.of(replayPath)),
                "run-3862-versioned-fulltext", outputPath);
    }

    private void export(RealGoldenDataset dataset,
                        Map<Long, ReplayArticle> replayArticles,
                        String bodySource,
                        Path outputPath) throws IOException {
        IssueClusteringProperties configuredProperties = new IssueClusteringProperties();
        double configuredCommonEntityRatio = configuredProperties.getCommonEntityDocumentRatio();
        validate(dataset, configuredCommonEntityRatio);
        List<GoldenArticle> articles = dataset.articles().stream()
                .map(source -> toGoldenArticle(source, replayArticles))
                .toList();

        List<RatioEvaluation> evaluations = dataset.commonEntityDocumentRatioCandidates().stream()
                .map(ratio -> evaluate(articles, ratio))
                .toList();
        RatioEvaluation configured = evaluations.stream()
                .filter(evaluation -> close(evaluation.commonEntityDocumentRatio(),
                        configuredCommonEntityRatio))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "프로덕션 common entity ratio 후보가 없습니다: " + configuredCommonEntityRatio));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("datasetVersion", dataset.datasetVersion());
        output.put("articleCount", articles.size());
        output.put("postHocRelabeledSourceArticleIds",
                dataset.postHocRelabeledSourceArticleIds());
        output.put("configuredTitleJaccardThreshold",
                configuredProperties.getTitleJaccardThreshold());
        output.put("configuredEntityTimeWindowHours",
                configuredProperties.getEntityTimeWindow().toHours());
        output.put("configuredEntityOverlapThreshold",
                configuredProperties.getEntityOverlapThreshold());
        output.put("configuredBreakingTimeWindowHours",
                configuredProperties.getBreakingTimeWindow().toHours());
        output.put("configuredOrganizationTimeWindowHours",
                configuredProperties.getOrganizationTimeWindow().toHours());
        output.put("configuredOrganizationTitleJaccardThreshold",
                configuredProperties.getOrganizationTitleJaccardThreshold());
        output.put("configuredMinArticleContentLength",
                configuredProperties.getMinArticleContentLength());
        output.put("configuredCommonEntityDocumentRatio",
                configuredProperties.getCommonEntityDocumentRatio());
        output.put("bodySource", bodySource);
        output.put("articles", configured.articles());
        output.put("pairEvaluations", evaluations.stream().map(evaluation -> Map.of(
                "commonEntityDocumentRatio", evaluation.commonEntityDocumentRatio(),
                "pairs", evaluation.pairs())).toList());

        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(output) + System.lineSeparator());

        assertEquals(56, articles.size());
        assertEquals(4, evaluations.size());
        assertFalse(configured.pairs().isEmpty());
    }

    private RatioEvaluation evaluate(List<GoldenArticle> articles, double ratio) {
        IssueClusterer clusterer = new IssueClusterer(properties(ratio), new BreakingNewsDetector());
        ClusterPlan plan = clusterer.cluster(
                articles.stream().map(GoldenArticle::article).toList(), true);

        Map<Long, String> contentGroupByArticle = new HashMap<>();
        Map<Long, Long> contentGroupRepresentativeByArticle = new HashMap<>();
        for (int index = 0; index < plan.contentGroups().size(); index++) {
            String groupKey = "content-" + index;
            ClusterPlan.ContentGroupAssignment group = plan.contentGroups().get(index);
            group.articleIds().forEach(articleId -> {
                contentGroupByArticle.put(articleId, groupKey);
                contentGroupRepresentativeByArticle.put(articleId, group.representativeArticleId());
            });
        }
        List<Map<String, Object>> exportedArticles = articles.stream().map(article -> {
            RealGoldenArticle source = article.source();
            ClusterArticle clustered = article.article();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("articleId", clustered.articleId());
            value.put("sourceRunId", source.sourceRunId());
            value.put("sourceArticleId", source.sourceArticleId());
            value.put("topicId", clustered.topicId());
            value.put("title", clustered.title());
            value.put("expectedIssueId", source.expectedIssueId());
            value.put("split", source.split());
            value.put("fixedContentGroupId",
                    contentGroupByArticle.get(clustered.articleId()));
            value.put("fixedContentGroupRepresentativeId",
                    contentGroupRepresentativeByArticle.get(clustered.articleId()));
            return value;
        }).toList();

        List<Map<String, Object>> exportedPairs = plan.pairScores().stream().map(score -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("leftArticleId", score.leftArticleId());
            value.put("rightArticleId", score.rightArticleId());
            value.put("topicId", score.topicId());
            value.put("titleJaccard", score.titleJaccard());
            value.put("entityOverlap", score.entityOverlap());
            value.put("organizationOverlap", score.organizationOverlap());
            value.put("breakingPair", score.breakingPair());
            value.put("hoursApart", score.hoursApart());
            return value;
        }).toList();
        return new RatioEvaluation(ratio, exportedArticles, exportedPairs);
    }

    private IssueClusteringProperties properties(double ratio) {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setCommonEntityDocumentRatio(ratio);
        return properties;
    }

    private RealGoldenDataset load() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/golden/clusters.v2.json")) {
            assertNotNull(input);
            return new ObjectMapper().readValue(input, RealGoldenDataset.class);
        }
    }

    private void validate(RealGoldenDataset dataset, double configuredCommonEntityRatio) {
        assertEquals("clusters.v2", dataset.datasetVersion());
        assertEquals(List.of(3862L), dataset.sourceRuns());
        assertTrue(dataset.articles().stream()
                .map(RealGoldenArticle::sourceArticleId)
                .collect(Collectors.toSet())
                .containsAll(dataset.postHocRelabeledSourceArticleIds()),
                "사후 정정한 sourceArticleId는 fixture에 있어야 한다.");
        assertTrue(dataset.commonEntityDocumentRatioCandidates().stream()
                .anyMatch(candidate -> close(candidate, configuredCommonEntityRatio)),
                "프로덕션 common entity ratio가 사전 고정 후보에 포함돼야 한다: "
                        + configuredCommonEntityRatio);

        Set<Long> articleIds = new HashSet<>();
        Set<Long> sourceArticleIds = new HashSet<>();
        dataset.articles().forEach(article -> {
            assertTrue(articleIds.add(article.articleId()),
                    "fixture articleId 중복: " + article.articleId());
            assertTrue(sourceArticleIds.add(article.sourceArticleId()),
                    "sourceArticleId 중복: " + article.sourceArticleId());
            assertTrue(article.body().length() <= 120,
                    "원문 전체가 아니라 최소 재현 조각만 보존한다: " + article.sourceArticleId());
        });

        dataset.articles().stream().collect(Collectors.groupingBy(RealGoldenArticle::topicId))
                .forEach((topicId, values) -> assertTrue(values.size() >= 20,
                        "공통 엔티티 DF 컷을 작동시키려면 주제별 20건 이상이어야 한다: " + topicId));

        Map<String, Set<String>> splitsByIssue = dataset.articles().stream().collect(
                Collectors.groupingBy(
                        article -> article.topicId() + ":" + article.expectedIssueId(),
                        Collectors.mapping(RealGoldenArticle::split, Collectors.toSet())));
        splitsByIssue.forEach((issue, splits) -> assertEquals(1, splits.size(),
                "같은 사건을 calibration/holdout에 나누면 누수다: " + issue));
        assertEquals(Set.of("CALIBRATION", "HOLDOUT"), dataset.articles().stream()
                .map(RealGoldenArticle::split)
                .collect(Collectors.toSet()));
    }

    private Map<Long, ReplayArticle> loadReplayArticles(RealGoldenDataset dataset,
                                                        Path replayPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<Long, ReplayArticle> result = new HashMap<>();
        for (String line : Files.readAllLines(replayPath)) {
            if (line.isBlank()) {
                continue;
            }
            ReplayArticle replayArticle = mapper.readValue(line, ReplayArticle.class);
            assertTrue(result.putIfAbsent(replayArticle.id(), replayArticle) == null,
                    "replay sourceArticleId 중복: " + replayArticle.id());
        }
        Set<Long> fixtureSourceArticleIds = dataset.articles().stream()
                .map(RealGoldenArticle::sourceArticleId)
                .collect(Collectors.toSet());
        assertEquals(fixtureSourceArticleIds, result.keySet(),
                "replay 입력은 clusters.v2의 sourceArticleId와 정확히 일치해야 한다.");
        return result;
    }

    private GoldenArticle toGoldenArticle(RealGoldenArticle source,
                                          Map<Long, ReplayArticle> replayArticles) {
        OffsetDateTime publishedAt = OffsetDateTime.parse(source.publishedAt());
        ReplayArticle replayArticle = replayArticles.get(source.sourceArticleId());
        String title = replayArticle == null ? source.title() : replayArticle.title();
        String summary = replayArticle != null && replayArticle.versioned() == 1
                ? null
                : source.summary();
        String body = replayArticle == null ? source.body() : replayArticle.body();
        ClusterArticle article = new ClusterArticle(
                source.articleId(),
                source.topicId(),
                title,
                summary,
                body,
                source.fetchStatus(),
                source.articleId(),
                source.publisher(),
                source.reliabilityScore(),
                publishedAt,
                publishedAt,
                source.topicKeywords(),
                null,
                null,
                null,
                true);
        return new GoldenArticle(article, source);
    }

    private boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000_001;
    }

    private record RealGoldenDataset(
            String datasetVersion,
            String description,
            List<Long> sourceRuns,
            List<Long> postHocRelabeledSourceArticleIds,
            List<Double> commonEntityDocumentRatioCandidates,
            List<RealGoldenArticle> articles
    ) {
    }

    private record RealGoldenArticle(
            long articleId,
            long sourceRunId,
            long sourceArticleId,
            long topicId,
            String expectedIssueId,
            String split,
            String title,
            String summary,
            String body,
            FetchStatus fetchStatus,
            String publisher,
            BigDecimal reliabilityScore,
            String publishedAt,
            List<String> topicKeywords
    ) {
    }

    private record GoldenArticle(ClusterArticle article, RealGoldenArticle source) {
    }

    private record ReplayArticle(long id, int versioned, String title, String body) {
    }

    private record RatioEvaluation(
            double commonEntityDocumentRatio,
            List<Map<String, Object>> articles,
            List<Map<String, Object>> pairs
    ) {
    }
}
