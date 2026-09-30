package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedProductEventEvidenceTest {
    private final BreakingNewsDetector detector = new BreakingNewsDetector();

    @Test
    void successorLaunchDoesNotJoinAnEarlierLaunchThroughItsBackgroundReferences() {
        ClusterArticle earlier = article(1, "오픈AI, GPT-6 솔·루나 출시",
                "오픈AI는 새 AI 모델 GPT-6 솔과 루나를 출시했다. 코딩 오류와 이용 가격을 줄였다.", 0);
        ClusterArticle successor = article(2, "오픈AI, 'GPT-6.1 솔' 공개…성능과 가격 개선",
                "오픈AI가 새로운 AI 모델 GPT-6.1 솔을 공개했다.\n"
                        + "지난 22일 GPT-6 솔을 공개한 뒤 내놓은 후속 모델이다. 앤트로픽 제품과도 비교했다.", 44);
        assertTrue(evidence(earlier, successor).conflicts(1, 2));
        assertTrue(evidence(earlier, successor).conflicts(2, 1));
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(0); // Force a positive lexical edge; the version guard must veto it.
        IssueClusterer clusterer = new IssueClusterer(properties, detector);
        for (List<ClusterArticle> input : List.of(List.of(earlier, successor), List.of(successor, earlier))) {
            assertFalse(together(clusterer.cluster(input), 1, 2));
        }
    }

    @Test
    void sameVersionSurvivesCaseSpacingAndHyphenVariantsWithEarlierVersionBackground() {
        ClusterArticle first = article(1, "Acme, Atlas-2.3 모델 공개",
                "Acme는 새로운 모델 Atlas-2.3을 공개했다.\n이전 Atlas-2.2 모델은 지난해 출시했다.", 0);
        for (String variant : List.of("ATLAS 2.3", "Atlas2.3", "Atlas v2.3")) {
            ClusterArticle second = article(2, "Acme, " + variant + " 모델 출시",
                    "Acme는 신형 모델 " + variant + "을 출시했다. 이전 버전보다 도구 사용을 개선했다.", 1);
            assertFalse(evidence(first, second).conflicts(1, 2), variant);
            assertTrue(together(new IssueClusterer(new IssueClusteringProperties(), detector)
                    .cluster(List.of(first, second)), 1, 2), variant);
        }
    }

    @Test
    void decimalConflictIsGenericAndSupportsEnglishVerbBeforeTheProduct() {
        ClusterArticle first = article(1, "Acme releases Atlas 2.3 model",
                "Acme released a new model Atlas 2.3 with improved tool use.", 0);
        ClusterArticle second = article(2, "Acme unveils Atlas 2.4 model",
                "Acme unveiled its model Atlas 2.4. The company compared it with Atlas 2.3.", 1);
        assertTrue(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void aComparisonCannotBorrowTheNewerProductsLaunchPredicate() {
        ClusterArticle first = article(1, "Acme, Atlas 2.4 공개…Atlas 2.3보다 개선",
                "Acme는 기존 모델 Atlas 2.3보다 발전한 Atlas 2.4를 공개했다.", 0);
        ClusterArticle sameRelease = article(2, "Acme, Atlas 2.4 모델 출시",
                "Acme는 새로운 모델 Atlas 2.4를 출시했다.", 1);
        assertFalse(evidence(first, sameRelease).conflicts(1, 2));
        ClusterArticle older = article(2, "Acme, Atlas 2.3 모델 출시",
                "Acme는 새로운 모델 Atlas 2.3을 출시했다.", 1);
        assertTrue(evidence(first, older).conflicts(1, 2));
    }

    @Test
    void backgroundLaunchCannotReplaceThePrimaryArticleFocus() {
        ClusterArticle first = article(1, "Acme, Atlas 2.4 공개",
                "Acme는 신형 모델 Atlas 2.4를 공개했다.", 0);
        for (ClusterArticle uncertain : List.of(
                article(2, "Atlas 2.3 모델 성능 분석", "이전 Atlas 2.3 모델을 공개했다. 현재는 성능을 분석한다.", 1),
                article(2, "Atlas 2.3 모델 공개 전망", "새 모델 Atlas 2.3을 공개할 것으로 예상된다.", 1),
                article(2, "Atlas 2.3 관련 발표", "도구 사용의 측정 방식을 논의했다.\n앞서 Atlas 2.3 모델을 공개했다.", 1))) {
            assertFalse(evidence(first, uncertain).conflicts(1, 2));
        }
    }

    @Test
    void anUnknownVersionCannotBridgeExplicitlyDifferentReleasesOrSpreadSavedMistakes() {
        ClusterArticle earlier = article(1, "Acme, Atlas 2.3 모델 공개",
                "Acme는 신형 모델 Atlas 2.3을 공개했다.", 0);
        ClusterArticle bridge = article(2, "Acme 모델 출시 소식",
                "Acme 모델의 출시 소식을 확인했다. 정확한 버전은 아직 알려지지 않았다.", 1);
        ClusterArticle successor = article(3, "Acme, Atlas 2.4 모델 공개",
                "Acme는 신형 모델 Atlas 2.4를 공개했다.", 2);
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(0);
        IssueClusterer clusterer = new IssueClusterer(properties, detector);
        for (List<ClusterArticle> input : List.of(List.of(earlier, bridge, successor),
                List.of(successor, bridge, earlier))) {
            assertFalse(together(clusterer.cluster(input), 1, 3));
        }
        ClusterArticle savedSuccessor = withIssue(successor, 90L);
        ClusterArticle anotherSuccessor = article(4, "Acme, Atlas 2.4 모델 출시",
                "Acme는 새로운 모델 Atlas 2.4를 출시했다.", 3);
        ClusterPlan plan = clusterer.cluster(List.of(withIssue(earlier, 90L), savedSuccessor, anotherSuccessor));
        assertTrue(together(plan, 1, 3)); // Retain saved membership, without spreading its earlier mistake.
        assertFalse(together(plan, 1, 4));
        assertFalse(together(plan, 3, 4));
    }

    @Test
    void nearlyIdenticalLaunchTemplatesCannotBypassVersionConflictsViaSimhash() {
        String template = "The product team documented tool operation, response accuracy and deployment costs. ".repeat(30);
        ClusterArticle first = article(1, "Acme, Atlas 2.3 모델 공개",
                "Acme는 새로운 모델 Atlas 2.3을 공개했다.\n" + template, 0);
        ClusterArticle second = article(2, "Acme, Atlas 2.4 모델 공개",
                "Acme는 새로운 모델 Atlas 2.4를 공개했다.\n" + template, 1);
        assertTrue(SimHash.distance(SimHash.tryOfArticleBody(first.body(), 200).orElseThrow(),
                SimHash.tryOfArticleBody(second.body(), 200).orElseThrow()) <= 3);
        ClusterPlan plan = new IssueClusterer(new IssueClusteringProperties(), detector).cluster(List.of(first, second));
        assertFalse(together(plan, 1, 2));
        assertTrue(plan.contentGroups().isEmpty());
    }

    @Test
    void aVersionlessDuplicateTemplateCannotBridgeDifferentVersionedContentGroups() {
        String template = "The model team documented tool operation, response accuracy and deployment costs. ".repeat(30);
        ClusterArticle first = article(1, "Acme, Atlas 2.3 모델 공개",
                "Acme는 새로운 모델 Atlas 2.3을 공개했다.\n" + template, 0);
        ClusterArticle bridge = article(2, "Acme 모델 소식",
                "Acme는 모델의 도구 사용에 관한 내용을 설명했다.\n" + template, 1);
        ClusterArticle second = article(3, "Acme, Atlas 2.4 모델 공개",
                "Acme는 새로운 모델 Atlas 2.4를 공개했다.\n" + template, 2);
        for (List<ClusterArticle> input : List.of(List.of(first, bridge, second), List.of(second, bridge, first))) {
            ClusterPlan plan = new IssueClusterer(new IssueClusteringProperties(), detector).cluster(input);
            assertFalse(together(plan, 1, 3));
            assertFalse(plan.contentGroups().stream().anyMatch(group -> group.articleIds().containsAll(List.of(1L, 3L))));
        }
    }

    private static ClusterArticle withIssue(ClusterArticle article, long issueId) {
        return new ClusterArticle(article.articleId(), article.topicId(), article.title(), article.summary(), article.body(),
                article.fetchStatus(), article.sourceId(), article.publisher(), article.reliabilityScore(),
                article.publishedAt(), article.observedAt(), article.topicKeywords(), article.contentGroupId(),
                article.contentGroupSimhash(), issueId, article.observedInRun());
    }

    private VersionedProductEventEvidence evidence(ClusterArticle... articles) {
        return new VersionedProductEventEvidence(List.of(articles), detector);
    }

    private static boolean together(ClusterPlan plan, long first, long second) {
        return plan.issues().stream().anyMatch(issue -> issue.articleIds().containsAll(List.of(first, second)));
    }

    private static ClusterArticle article(long id, String title, String body, long hours) {
        OffsetDateTime time = OffsetDateTime.parse("2026-09-28T08:08:07+09:00").plusHours(hours);
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT, id, "fixture-" + id,
                null, time, time, List.of("Acme", "오픈AI", "앤트로픽"), null, null, null, true);
    }
}
