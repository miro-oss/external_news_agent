package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frozen, independently labeled input only. No database access or existing cluster membership. */
class IssueClustererIndependentExportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SPLITS = List.of("CALIBRATION", "HOLDOUT");
    private static final List<Double> RATIOS = List.of(0.05, 0.10, 0.15, 0.20);

    @Test
    @EnabledIfEnvironmentVariable(named = "CLUSTERS_INDEPENDENT_GOLDEN", matches = ".+")
    void exportsFrozenIndependentGoldenFeatures() throws IOException {
        String configuredOutput = System.getenv("CLUSTERS_INDEPENDENT_OUTPUT");
        require(configuredOutput != null && !configuredOutput.isBlank(),
                "CLUSTERS_INDEPENDENT_OUTPUT is required; keep full-body evaluation artifacts outside Git.");
        Path inputPath = Path.of(System.getenv("CLUSTERS_INDEPENDENT_GOLDEN"));
        Path outputPath = Path.of(configuredOutput);
        require(!inputPath.toAbsolutePath().normalize().equals(outputPath.toAbsolutePath().normalize()),
                "Golden input and feature output must be different files.");
        require(!Files.exists(outputPath) || !Files.isSameFile(inputPath, outputPath),
                "Feature output must not overwrite the frozen golden input through a link.");
        export(Files.readAllBytes(inputPath), outputPath);
    }

    private void export(byte[] goldenBytes, Path outputPath) throws IOException {
        IssueClusteringProperties configuredProperties = new IssueClusteringProperties();
        Dataset dataset = load(goldenBytes, configuredProperties.getCommonEntityDocumentRatio());
        List<RatioEvaluation> evaluations = dataset.ratios().stream()
                .map(ratio -> evaluate(dataset, ratio))
                .toList();
        RatioEvaluation configured = evaluations.stream()
                .filter(value -> close(value.ratio(), configuredProperties.getCommonEntityDocumentRatio()))
                .findFirst().orElseThrow();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("datasetVersion", dataset.version());
        output.put("clusteringRuleVersion", "title-organization-conflict-v1");
        output.put("goldenSha256", sha256(goldenBytes));
        output.put("sourceRuns", dataset.sourceRuns());
        output.put("articleCount", dataset.articles().size());
        output.put("documentFrequencyScope", "SPLIT");
        output.put("contentGroupingScope", "SPLIT");
        output.put("documentFrequencyByTopic", configured.documentFrequencyByTopic());
        output.put("configuredTitleJaccardThreshold", configuredProperties.getTitleJaccardThreshold());
        output.put("configuredEntityTimeWindowHours", configuredProperties.getEntityTimeWindow().toHours());
        output.put("configuredEntityOverlapThreshold", configuredProperties.getEntityOverlapThreshold());
        output.put("configuredBreakingTimeWindowHours", configuredProperties.getBreakingTimeWindow().toHours());
        output.put("configuredOrganizationTimeWindowHours",
                configuredProperties.getOrganizationTimeWindow().toHours());
        output.put("configuredOrganizationTitleJaccardThreshold",
                configuredProperties.getOrganizationTitleJaccardThreshold());
        output.put("configuredMinArticleContentLength", configuredProperties.getMinArticleContentLength());
        output.put("configuredCommonEntityDocumentRatio", configuredProperties.getCommonEntityDocumentRatio());
        output.put("bodySource", "frozen-independent-golden");
        output.put("articles", configured.articles());
        output.put("pairEvaluations", evaluations.stream().map(value -> Map.of(
                "commonEntityDocumentRatio", value.ratio(), "pairs", value.pairs())).toList());

        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(output) + System.lineSeparator());
    }

    private RatioEvaluation evaluate(Dataset dataset, double ratio) {
        List<Map<String, Object>> exportedArticles = new ArrayList<>();
        List<Map<String, Object>> exportedPairs = new ArrayList<>();
        List<Map<String, Object>> documentFrequencyByTopic = new ArrayList<>();
        for (String split : SPLITS) {
            // Compute both content groups and topic DF afresh inside each split.
            // Computing once and filtering pairs afterwards leaks holdout document frequencies.
            List<GoldenArticle> articles = dataset.articles().stream()
                    .filter(article -> article.split().equals(split)).toList();
            IssueClusteringProperties properties = new IssueClusteringProperties();
            properties.setCommonEntityDocumentRatio(ratio);
            ClusterPlan plan = new IssueClusterer(properties, new BreakingNewsDetector())
                    .cluster(articles.stream().map(GoldenArticle::article).toList(), true);
            Map<Long, Long> issueRepresentativeByArticle = new HashMap<>();
            plan.issues().forEach(issue -> issue.articleIds().forEach(articleId ->
                    issueRepresentativeByArticle.put(articleId, issue.representativeArticleId())));
            Map<Long, String> contentGroupByArticle = new HashMap<>();
            Map<Long, Long> representativeByArticle = new HashMap<>();
            for (int index = 0; index < plan.contentGroups().size(); index++) {
                String groupId = split + ":content-" + index;
                ClusterPlan.ContentGroupAssignment group = plan.contentGroups().get(index);
                group.articleIds().forEach(articleId -> {
                    contentGroupByArticle.put(articleId, groupId);
                    representativeByArticle.put(articleId, group.representativeArticleId());
                });
            }
            for (GoldenArticle source : articles) {
                ClusterArticle article = source.article();
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("articleId", article.articleId());
                value.put("sourceArticleId", source.sourceArticleId());
                value.put("sourceRunId", source.sourceRunId());
                value.put("sourceRunIds", source.sourceRunIds());
                value.put("sourceId", article.sourceId());
                value.put("topicId", article.topicId());
                value.put("title", article.title());
                value.put("titleOrganizations", new DeterministicEntityExtractor()
                        .extractTitleOrganizations(new BreakingNewsDetector().coreTitle(article.title()))
                        .stream().sorted().toList());
                value.put("expectedIssueId", source.expectedIssueId());
                value.put("split", source.split());
                value.put("observedAt", article.observedAt() == null ? null : article.observedAt().toString());
                value.put("fixedContentGroupId", contentGroupByArticle.get(article.articleId()));
                value.put("fixedContentGroupRepresentativeId", representativeByArticle.get(article.articleId()));
                // Actual Java membership supports regression inspection without reconstructing pair unions.
                value.put("configuredIssueRepresentativeId", issueRepresentativeByArticle.get(article.articleId()));
                exportedArticles.add(value);
            }
            plan.pairScores().forEach(score -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("leftArticleId", score.leftArticleId());
                value.put("rightArticleId", score.rightArticleId());
                value.put("topicId", score.topicId());
                value.put("split", split);
                value.put("titleJaccard", score.titleJaccard());
                value.put("entityOverlap", score.entityOverlap());
                value.put("organizationOverlap", score.organizationOverlap());
                value.put("breakingPair", score.breakingPair());
                value.put("hoursApart", score.hoursApart());
                exportedPairs.add(value);
            });
            articles.stream().collect(Collectors.groupingBy(
                            article -> article.article().topicId(), LinkedHashMap::new, Collectors.toList()))
                    .forEach((topicId, topicArticles) -> {
                        long votingCount = topicArticles.stream().map(article -> representativeByArticle
                                        .getOrDefault(article.article().articleId(), article.article().articleId()))
                                .distinct().count();
                        documentFrequencyByTopic.add(Map.of(
                                "split", split, "topicId", topicId, "articleCount", topicArticles.size(),
                                "votingRepresentativeCount", votingCount,
                                "minimumArticles", properties.getCommonEntityMinArticles(),
                                "documentFrequencyActive", votingCount >= properties.getCommonEntityMinArticles()));
                    });
        }
        return new RatioEvaluation(ratio, exportedArticles, exportedPairs, documentFrequencyByTopic);
    }

    private Dataset load(byte[] bytes, double configuredRatio) throws IOException {
        JsonNode root = MAPPER.readTree(bytes);
        require(root != null && root.isObject(), "Golden input must be a JSON object.");
        String version = requiredText(root, "datasetVersion");
        List<Long> sourceRuns = positiveIds(requiredArray(root, "sourceRuns"), "sourceRuns");
        require(new HashSet<>(sourceRuns).size() == sourceRuns.size() && sourceRuns.size() >= 3,
                "At least three distinct sourceRuns are required.");
        List<Double> ratios = new ArrayList<>();
        for (JsonNode value : requiredArray(root, "commonEntityDocumentRatioCandidates")) {
            require(value.isNumber(), "DF ratio candidates must be numbers.");
            ratios.add(value.doubleValue());
        }
        require(ratios.size() == RATIOS.size() && RATIOS.stream().allMatch(expected ->
                        ratios.stream().filter(value -> close(value, expected)).count() == 1),
                "Precommitted DF ratios must be exactly [0.05, 0.10, 0.15, 0.20].");
        require(ratios.stream().anyMatch(value -> close(value, configuredRatio)),
                "Configured DF ratio must be in the precommitted candidates.");
        List<GoldenArticle> articles = new ArrayList<>();
        for (JsonNode value : requiredArray(root, "articles")) {
            articles.add(readArticle(value));
        }
        Dataset dataset = new Dataset(version, sourceRuns, ratios, articles);
        validate(dataset);
        return dataset;
    }

    private GoldenArticle readArticle(JsonNode source) {
        long articleId = positiveId(source.path("articleId"), "articleId");
        long sourceRunId = positiveId(source.path("sourceRunId"), "sourceRunId");
        List<Long> sourceRunIds = source.hasNonNull("sourceRunIds")
                ? positiveIds(requiredArray(source, "sourceRunIds"), "sourceRunIds")
                : List.of(sourceRunId);
        require(sourceRunIds.contains(sourceRunId)
                        && new HashSet<>(sourceRunIds).size() == sourceRunIds.size(),
                "sourceRunIds must be distinct and include sourceRunId: " + articleId);
        OffsetDateTime publishedAt = optionalTime(source, "publishedAt");
        OffsetDateTime observedAt = optionalTime(source, "observedAt");
        if (observedAt == null) {
            observedAt = publishedAt;
        }
        List<String> topicKeywords = new ArrayList<>();
        for (JsonNode keyword : requiredArray(source, "topicKeywords")) {
            require(keyword.isString() && !keyword.asString().isBlank(), "topicKeywords must contain text.");
            topicKeywords.add(keyword.asString());
        }
        JsonNode reliability = source.path("reliabilityScore");
        require(reliability.isNumber() || reliability.isNull() || reliability.isMissingNode(),
                "reliabilityScore must be numeric or null: " + articleId);
        BigDecimal reliabilityScore = reliability.isNumber() ? reliability.decimalValue() : null;
        ClusterArticle article = new ClusterArticle(
                articleId, positiveId(source.path("topicId"), "topicId"), requiredText(source, "title"),
                optionalText(source, "summary"), optionalText(source, "body"),
                FetchStatus.valueOf(requiredText(source, "fetchStatus")),
                positiveId(source.path("sourceId"), "sourceId"), optionalText(source, "publisher"),
                reliabilityScore, publishedAt, observedAt, topicKeywords, null, null, null, true);
        return new GoldenArticle(article, positiveId(source.path("sourceArticleId"), "sourceArticleId"),
                sourceRunId, sourceRunIds, requiredText(source, "expectedIssueId"), requiredText(source, "split"));
    }

    private void validate(Dataset dataset) {
        Set<Long> articleIds = new HashSet<>();
        Set<SourceTopicKey> sourceTopicKeys = new HashSet<>();
        Map<String, String> splitByEvent = new HashMap<>();
        Map<Long, String> splitBySourceArticle = new HashMap<>();
        Set<Long> topics = new HashSet<>();
        for (GoldenArticle source : dataset.articles()) {
            ClusterArticle article = source.article();
            require(articleIds.add(article.articleId()), "Duplicate articleId: " + article.articleId());
            require(sourceTopicKeys.add(new SourceTopicKey(source.sourceArticleId(), article.topicId())),
                    "Duplicate (sourceArticleId, topicId): " + source.sourceArticleId() + ", " + article.topicId());
            require(dataset.sourceRuns().contains(source.sourceRunId())
                            && dataset.sourceRuns().containsAll(source.sourceRunIds()),
                    "Article provenance must belong to sourceRuns: " + article.articleId());
            require(SPLITS.contains(source.split()), "split must be CALIBRATION or HOLDOUT: " + article.articleId());
            String eventSplit = splitByEvent.putIfAbsent(source.expectedIssueId(), source.split());
            require(eventSplit == null || eventSplit.equals(source.split()),
                    "An expected event cannot cross splits, including across topics: " + source.expectedIssueId());
            String articleSplit = splitBySourceArticle.putIfAbsent(source.sourceArticleId(), source.split());
            require(articleSplit == null || articleSplit.equals(source.split()),
                    "A sourceArticleId cannot cross splits: " + source.sourceArticleId());
            topics.add(article.topicId());
        }
        require(topics.size() >= 2, "At least two topics are required.");
        for (String split : SPLITS) {
            long positivePairs = 0;
            long negativePairs = 0;
            for (long topicId : topics) {
                List<GoldenArticle> articles = dataset.articles().stream()
                        .filter(source -> source.split().equals(split) && source.article().topicId() == topicId)
                        .toList();
                require(articles.size() >= 20,
                        "At least 20 articles per topic and split are required: " + topicId + ", " + split);
                Map<String, Long> counts = articles.stream().collect(Collectors.groupingBy(
                        GoldenArticle::expectedIssueId, Collectors.counting()));
                long sameEventPairs = counts.values().stream().mapToLong(this::pairs).sum();
                positivePairs += sameEventPairs;
                negativePairs += pairs(articles.size()) - sameEventPairs;
            }
            require(positivePairs > 0 && negativePairs > 0,
                    "Each split requires positive and negative topic-scoped truth pairs: " + split);
        }
    }

    private long pairs(long count) {
        return count * (count - 1) / 2;
    }

    private ArrayNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        require(value.isArray(), field + " must be an array.");
        return (ArrayNode) value;
    }

    private List<Long> positiveIds(ArrayNode values, String field) {
        List<Long> result = new ArrayList<>();
        values.forEach(value -> result.add(positiveId(value, field)));
        return List.copyOf(result);
    }

    private long positiveId(JsonNode value, String field) {
        require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0,
                field + " must be a positive integer.");
        return value.longValue();
    }

    private String requiredText(JsonNode parent, String field) {
        String result = optionalText(parent, field);
        require(result != null && !result.isBlank(), field + " must be nonempty text.");
        return result;
    }

    private String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        require(value.isString(), field + " must be text or null.");
        return value.asString();
    }

    private OffsetDateTime optionalTime(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        return value == null ? null : OffsetDateTime.parse(value);
    }

    private boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000_001;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Test
    void exportsSyntheticSplitsWithoutSharingFeaturePopulations(@TempDir Path directory) throws IOException {
        ObjectNode input = syntheticInput();
        // This anchor is rare in calibration but ubiquitous in holdout. Shared DF would erase it.
        articleAt(input, 0).put("summary", "QZK77");
        articleAt(input, 1).put("summary", "QZK77");
        for (int index = 40; index < 60; index++) {
            articleAt(input, index).put("summary", "QZK77");
        }
        // Identical bodies across splits must remain two singletons, without a shared fixed group.
        String sharedBody = "A synthetic report describes a laboratory experiment with detailed observations. ".repeat(5);
        for (int index : List.of(0, 40)) {
            articleAt(input, index).put("body", sharedBody);
            articleAt(input, index).put("fetchStatus", "FULLTEXT");
        }
        byte[] bytes = MAPPER.writeValueAsBytes(input);
        Path output = directory.resolve("pairs.json");
        export(bytes, output);
        JsonNode result = MAPPER.readTree(Files.readAllBytes(output));
        assertEquals(80, result.path("articleCount").asInt());
        assertEquals(sha256(bytes), result.path("goldenSha256").asString());
        assertEquals("SPLIT", result.path("documentFrequencyScope").asString());
        assertEquals(4, result.path("pairEvaluations").size());
        Map<Long, String> splitById = new HashMap<>();
        result.path("articles").forEach(article -> {
            splitById.put(article.path("articleId").asLong(), article.path("split").asString());
            assertEquals(700, article.path("sourceId").asLong());
            assertEquals("2026-01-01T00:00Z", article.path("observedAt").asString());
            assertTrue(article.path("fixedContentGroupId").isNull());
        });
        for (JsonNode evaluation : result.path("pairEvaluations")) {
            assertEquals(760, evaluation.path("pairs").size());
            for (JsonNode pair : evaluation.path("pairs")) {
                assertEquals(splitById.get(pair.path("leftArticleId").asLong()),
                        splitById.get(pair.path("rightArticleId").asLong()));
                assertEquals(splitById.get(pair.path("leftArticleId").asLong()),
                        pair.path("split").asString());
                if (pair.path("leftArticleId").asLong() == 1 && pair.path("rightArticleId").asLong() == 2) {
                    assertEquals(1, pair.path("entityOverlap").asInt(), "Holdout DF must not affect calibration.");
                }
            }
        }
    }

    @Test
    void rejectsDuplicateRowsAndInvalidProvenance() {
        ObjectNode duplicateId = syntheticInput();
        articleAt(duplicateId, 1).put("articleId", 1);
        assertInvalid(duplicateId, "Duplicate articleId");
        ObjectNode duplicateSourceTopic = syntheticInput();
        articleAt(duplicateSourceTopic, 1).put("sourceArticleId", 1001);
        assertInvalid(duplicateSourceTopic, "Duplicate (sourceArticleId, topicId)");
        ObjectNode unknownRun = syntheticInput();
        articleAt(unknownRun, 0).put("sourceRunId", 999);
        assertInvalid(unknownRun, "provenance");
    }

    @Test
    void rejectsEventLeakageAcrossTopicsAndSplits() {
        ObjectNode input = syntheticInput();
        articleAt(input, 60).put("expectedIssueId", "CALIBRATION-1-event-0");
        assertInvalid(input, "event cannot cross splits");
    }

    @Test
    void rejectsInsufficientSplitPopulationOrTruthPairClasses() {
        ObjectNode tooSmall = syntheticInput();
        ((ArrayNode) tooSmall.path("articles")).remove(0);
        assertInvalid(tooSmall, "20 articles per topic and split");
        ObjectNode noPositivePairs = syntheticInput();
        for (int index = 0; index < 80; index++) {
            articleAt(noPositivePairs, index).put("expectedIssueId", "singleton-" + index);
        }
        assertInvalid(noPositivePairs, "positive and negative");
        ObjectNode noNegativePairs = syntheticInput();
        for (int index = 0; index < 80; index++) {
            ObjectNode article = articleAt(noNegativePairs, index);
            article.put("expectedIssueId", article.path("split").asString());
        }
        assertInvalid(noNegativePairs, "positive and negative");
    }

    @Test
    void rejectsMissingRunCoverageAndUncommittedRatioCandidates() {
        ObjectNode tooFewRuns = syntheticInput();
        ((ArrayNode) tooFewRuns.path("sourceRuns")).remove(0);
        assertInvalid(tooFewRuns, "three distinct sourceRuns");
        ObjectNode wrongRatio = syntheticInput();
        ((ArrayNode) wrongRatio.path("commonEntityDocumentRatioCandidates")).add(0.25);
        assertInvalid(wrongRatio, "Precommitted DF ratios");
    }

    private void assertInvalid(ObjectNode root, String expectedMessage) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> load(MAPPER.writeValueAsBytes(root), 0.10));
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
    }

    private ObjectNode articleAt(ObjectNode root, int index) {
        return (ObjectNode) root.path("articles").get(index);
    }

    private ObjectNode syntheticInput() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("datasetVersion", "independent-synthetic-validation");
        root.put("unrelatedMetadata", "accepted");
        ArrayNode runs = root.putArray("sourceRuns");
        List.of(11, 12, 13).forEach(runs::add);
        ArrayNode ratios = root.putArray("commonEntityDocumentRatioCandidates");
        RATIOS.forEach(ratios::add);
        ArrayNode articles = root.putArray("articles");
        long nextId = 1;
        for (String split : SPLITS) {
            for (long topicId : List.of(1L, 2L)) {
                for (int index = 0; index < 20; index++) {
                    ObjectNode article = articles.addObject();
                    article.put("articleId", nextId);
                    article.put("sourceArticleId", 1000 + nextId);
                    article.put("sourceRunId", 11 + nextId % 3);
                    article.put("sourceId", 700);
                    article.put("topicId", topicId);
                    article.put("title", "Synthetic article " + nextId);
                    article.putNull("body");
                    article.put("fetchStatus", "METADATA_ONLY");
                    article.put("publishedAt", "2026-01-01T00:00:00Z");
                    article.put("expectedIssueId", split + "-" + topicId + "-event-" + index / 2);
                    article.put("split", split);
                    article.putArray("topicKeywords").add("Synthetic");
                    nextId++;
                }
            }
        }
        assertFalse(articles.isEmpty());
        return root;
    }

    private record Dataset(String version, List<Long> sourceRuns, List<Double> ratios,
                           List<GoldenArticle> articles) {
    }

    private record GoldenArticle(ClusterArticle article, long sourceArticleId, long sourceRunId,
                                 List<Long> sourceRunIds, String expectedIssueId, String split) {
    }

    private record SourceTopicKey(long sourceArticleId, long topicId) {
    }

    private record RatioEvaluation(double ratio, List<Map<String, Object>> articles,
                                   List<Map<String, Object>> pairs,
                                   List<Map<String, Object>> documentFrequencyByTopic) {
    }
}
