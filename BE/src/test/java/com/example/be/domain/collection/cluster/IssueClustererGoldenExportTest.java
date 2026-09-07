package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererGoldenExportTest {

    private static final List<String> PUBLISHERS = List.of("전자신문", "연합뉴스", "매일경제", "산업일보");
    private static final List<String> BODY_MARKERS = List.of(
            "현장 취재 관계자 설명 생산 일정 공급 계약",
            "공시 자료 경영진 발표 투자 계획 세부 내용",
            "산업계 분석 시장 반응 고객사 협의 진행",
            "후속 보도 정책 영향 향후 전망 검증 결과");
    private static final List<Long> PUBLISHED_HOUR_OFFSETS = List.of(0L, 1L, 2L, 47L);

    @Test
    void exportsJavaFeaturesAndMeetsPairwisePrecisionGate() throws IOException {
        GoldenDataset dataset = load();
        List<GoldenArticle> goldenArticles = expand(dataset);
        assertEquals(200, goldenArticles.size());

        IssueClusteringProperties properties = new IssueClusteringProperties();
        BreakingNewsDetector breakingNewsDetector = new BreakingNewsDetector();
        IssueClusterer clusterer = new IssueClusterer(properties, breakingNewsDetector);
        ClusterPlan plan = clusterer.cluster(
                goldenArticles.stream().map(GoldenArticle::article).toList(), true);

        Map<Long, Integer> predictedClusterByArticle = predictedClusters(plan);
        Map<PairKey, ClusterPlan.PairScore> scores = new HashMap<>();
        plan.pairScores().forEach(score -> scores.put(
                new PairKey(score.leftArticleId(), score.rightArticleId()), score));

        long truePositive = 0;
        long falsePositive = 0;
        long falseNegative = 0;
        List<Map<String, Object>> exportedPairs = new ArrayList<>();
        for (int left = 0; left < goldenArticles.size(); left++) {
            for (int right = left + 1; right < goldenArticles.size(); right++) {
                GoldenArticle first = goldenArticles.get(left);
                GoldenArticle second = goldenArticles.get(right);
                if (first.article().topicId() != second.article().topicId()) {
                    continue;
                }
                boolean expected = first.issueKey().equals(second.issueKey());
                boolean predicted = predictedClusterByArticle.get(first.article().articleId())
                        .equals(predictedClusterByArticle.get(second.article().articleId()));
                if (expected && predicted) {
                    truePositive++;
                } else if (!expected && predicted) {
                    falsePositive++;
                } else if (expected) {
                    falseNegative++;
                }
                ClusterPlan.PairScore score = scores.get(new PairKey(
                        first.article().articleId(), second.article().articleId()));
                if (score == null) {
                    throw new IllegalStateException("Java pair feature가 없습니다.");
                }
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("leftArticleId", first.article().articleId());
                pair.put("rightArticleId", second.article().articleId());
                pair.put("topicId", first.article().topicId());
                pair.put("split", first.split().equals(second.split()) ? first.split() : "CROSS");
                pair.put("titleJaccard", score.titleJaccard());
                pair.put("titleTextSimilarity", score.titleTextSimilarity());
                pair.put("leadTextSimilarity", score.leadTextSimilarity());
                pair.put("eventTextMatch", score.eventTextMatch());
                pair.put("entityTitleSupported", score.entityTitleSupported());
                pair.put("organizationTitleSupported", score.organizationTitleSupported());
                pair.put("entityOverlap", score.entityOverlap());
                pair.put("organizationOverlap", score.organizationOverlap());
                pair.put("breakingPair", score.breakingPair());
                pair.put("hoursApart", score.hoursApart());
                pair.put("expectedSameIssue", expected);
                pair.put("predictedSameIssue", predicted);
                exportedPairs.add(pair);
            }
        }

        double precision = rate(truePositive, truePositive + falsePositive);
        double recall = rate(truePositive, truePositive + falseNegative);
        Map<Integer, List<String>> issueKeysByPredictedCluster = goldenArticles.stream().collect(
                java.util.stream.Collectors.groupingBy(
                        article -> predictedClusterByArticle.get(article.article().articleId()),
                        java.util.stream.Collectors.mapping(
                                GoldenArticle::issueKey,
                                java.util.stream.Collectors.toList())));
        List<List<String>> mixedClusters = issueKeysByPredictedCluster.values().stream()
                .filter(values -> values.stream().distinct().count() > 1)
                .toList();
        List<Map<String, Object>> exportedArticles = goldenArticles.stream().map(article -> {
            ClusterArticle clustered = article.article();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("articleId", clustered.articleId());
            value.put("topicId", clustered.topicId());
            value.put("title", clustered.title());
            value.put("titleOrganizations", clusterer.titleOrganizations(clustered.title())
                    .stream().sorted().toList());
            value.put("expectedIssueId", article.issueKey());
            value.put("split", article.split());
            value.put("predictedClusterId", predictedClusterByArticle.get(clustered.articleId()));
            return value;
        }).toList();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("datasetVersion", dataset.datasetVersion());
        output.put("clusteringRuleVersion", IssueClusterer.RULE_VERSION);
        output.put("articleCount", goldenArticles.size());
        output.put("configuredTitleJaccardThreshold", properties.getTitleJaccardThreshold());
        output.put("configuredEntityTimeWindowHours", properties.getEntityTimeWindow().toHours());
        output.put("configuredEntityOverlapThreshold", properties.getEntityOverlapThreshold());
        output.put("configuredBreakingTimeWindowHours", properties.getBreakingTimeWindow().toHours());
        output.put("configuredOrganizationTimeWindowHours",
                properties.getOrganizationTimeWindow().toHours());
        output.put("configuredOrganizationTitleJaccardThreshold",
                properties.getOrganizationTitleJaccardThreshold());
        output.put("configuredCommonEntityDocumentRatio", properties.getCommonEntityDocumentRatio());
        output.put("bodySource", "synthetic-fixture");
        output.put("precision", precision);
        output.put("recall", recall);
        output.put("articles", exportedArticles);
        output.put("pairs", exportedPairs);

        Path outputPath = Path.of("build/reports/clusters/pairs.json");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(output) + System.lineSeparator());

        assertTrue(precision >= 0.90,
                "pairwise precision=" + precision + " mixedClusters=" + mixedClusters);
        assertTrue(recall >= 0.85, "pairwise recall=" + recall);
    }

    private GoldenDataset load() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/golden/clusters.v1.json")) {
            return new ObjectMapper().readValue(input, GoldenDataset.class);
        }
    }

    private List<GoldenArticle> expand(GoldenDataset dataset) {
        List<GoldenArticle> result = new ArrayList<>();
        long articleId = 1;
        for (GoldenIssue issue : dataset.issues()) {
            List<String> titles = titleVariants(issue);
            for (int variant = 0; variant < dataset.articlesPerIssue(); variant++) {
                OffsetDateTime publishedAt = OffsetDateTime.parse(issue.publishedAt())
                        .plusHours(PUBLISHED_HOUR_OFFSETS.get(variant));
                String body = (titles.get(variant) + ". " + BODY_MARKERS.get(variant) + ". ")
                        .repeat(12);
                ClusterArticle article = new ClusterArticle(
                        articleId,
                        issue.topicId(),
                        titles.get(variant),
                        issue.summary(),
                        body,
                        FetchStatus.FULLTEXT,
                        articleId,
                        PUBLISHERS.get(variant),
                        new BigDecimal("0.80").add(BigDecimal.valueOf(variant, 2)),
                        publishedAt,
                        publishedAt,
                        List.of(issue.topicId() == 1 ? "반도체" : "제조"),
                        null,
                        null,
                        null,
                        true);
                result.add(new GoldenArticle(article, issue.issueKey(), issue.split()));
                articleId++;
            }
        }
        return List.copyOf(result);
    }

    private List<String> titleVariants(GoldenIssue issue) {
        List<String> tokens = List.of(issue.baseTitle().split("\\s+"));
        String announcement = String.join(" ", tokens.subList(0, tokens.size() - 1)) + " 공식 발표";
        String followUp = tokens.getFirst() + " "
                + String.join(" ", tokens.subList(2, tokens.size())) + " 업계 분석";
        String lateTitle = issue.issueKey().endsWith("05")
                ? indexedTokens(tokens, 1) + " 후속 확인"
                : String.join(" ", tokens.subList(1, tokens.size())) + " 후속 확인";
        return List.of(
                issue.baseTitle(),
                announcement,
                followUp,
                lateTitle);
    }

    private String indexedTokens(List<String> tokens, int parity) {
        List<String> selected = new ArrayList<>();
        for (int index = parity; index < tokens.size(); index += 2) {
            selected.add(tokens.get(index));
        }
        return String.join(" ", selected);
    }

    private Map<Long, Integer> predictedClusters(ClusterPlan plan) {
        Map<Long, Integer> result = new HashMap<>();
        for (int clusterIndex = 0; clusterIndex < plan.issues().size(); clusterIndex++) {
            int value = clusterIndex;
            plan.issues().get(clusterIndex).articleIds().forEach(articleId -> result.put(articleId, value));
        }
        return Map.copyOf(result);
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    private record GoldenDataset(
            String datasetVersion,
            String description,
            int articlesPerIssue,
            List<GoldenIssue> issues
    ) {
    }

    private record GoldenIssue(
            String issueKey,
            long topicId,
            String split,
            String baseTitle,
            String summary,
            String publishedAt
    ) {
    }

    private record GoldenArticle(ClusterArticle article, String issueKey, String split) {
    }

    private record PairKey(long left, long right) {

        private PairKey {
            if (left > right) {
                long swap = left;
                left = right;
                right = swap;
            }
        }
    }
}
