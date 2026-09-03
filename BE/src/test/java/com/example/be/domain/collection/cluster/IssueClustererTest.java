package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererTest {

    private IssueClusterer clusterer;

    @BeforeEach
    void setUp() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        clusterer = new IssueClusterer(properties, new BreakingNewsDetector());
    }

    @Test
    void groupsSyndicatedFullTextWithoutDroppingPublisherRows() {
        String original = longBody("삼성전자가 HBM4 양산 계획을 발표했다");
        String syndicated = original.replaceFirst("계획", "일정");
        ClusterArticle first = article(
                1L, "삼성전자 HBM4 양산 계획 발표", original,
                FetchStatus.FULLTEXT, "전자신문", "0.85", hour(0));
        ClusterArticle second = article(
                2L, "[속보] 삼성전자 HBM4 양산 계획 발표 - 연합뉴스", syndicated,
                FetchStatus.FULLTEXT, "연합뉴스", "0.95", hour(1));
        ClusterArticle unrelated = article(
                3L, "인텔 오하이오 공장 보조금 협상 재개", longBody("인텔이 공장 보조금 협상을 재개했다"),
                FetchStatus.FULLTEXT, "로이터", "0.90", hour(2));

        ClusterPlan plan = clusterer.cluster(List.of(first, second, unrelated));

        assertEquals(1, plan.contentGroups().size());
        assertEquals(List.of(1L, 2L), plan.contentGroups().getFirst().articleIds());
        assertEquals(2, plan.issues().size());
        ClusterPlan.IssueAssignment grouped = plan.issues().stream()
                .filter(issue -> issue.articleIds().contains(1L))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(1L, 2L), grouped.articleIds());
        assertEquals(2, grouped.publisherCount());
        assertEquals(1, grouped.independentContentCount());
        assertEquals(1L, grouped.representativeArticleId());
    }

    @Test
    void neverCreatesContentGroupFromMetadataOnlySimilarity() {
        ClusterArticle first = article(
                1L, "삼성전자 HBM4 공급 일정 발표", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "삼성전자 HBM4 공급 전망 공개", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(1));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertTrue(plan.contentGroups().isEmpty());
    }

    @Test
    void excludesBoilerplateOnlyFullTextFromContentGrouping() {
        ClusterArticle first = article(
                1L, "현대로템 장갑형 구급차 개발 완료", ClusterTestFixtures.PUBLISHER_FOOTER,
                FetchStatus.FULLTEXT, "뉴시스", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "SK온 미국 ESS 공급계약 체결", ClusterTestFixtures.PUBLISHER_FOOTER,
                FetchStatus.FULLTEXT, "뉴시스", "0.8", hour(72));

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertTrue(plan.contentGroups().isEmpty());
        assertEquals(2, plan.issues().size());
    }

    @Test
    void doesNotReuseExistingContentGroupForBoilerplateOnlyBody() {
        ClusterArticle first = articleWithContentGroup(
                1L, "현대로템 장갑형 구급차 개발 완료",
                ClusterTestFixtures.PUBLISHER_FOOTER, 10L, hour(0));
        ClusterArticle second = articleWithContentGroup(
                2L, "SK온 미국 ESS 공급계약 체결",
                ClusterTestFixtures.PUBLISHER_FOOTER, 10L, hour(72));

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertTrue(plan.contentGroups().isEmpty());
        assertEquals(2, plan.issues().size());
    }

    @Test
    void isolatesBoilerplateMemberFromValidArticleInExistingContentGroup() {
        ClusterArticle valid = articleWithContentGroup(
                1L, "삼성전자 HBM4 공급 계약", longBody("삼성전자 HBM4 공급 계약"), 10L, hour(0));
        ClusterArticle boilerplate = articleWithContentGroup(
                2L, "현대로템 장갑형 구급차 개발 완료",
                ClusterTestFixtures.PUBLISHER_FOOTER, 10L, hour(72));

        ClusterPlan plan = clusterer.cluster(List.of(valid, boilerplate));

        assertEquals(1, plan.contentGroups().size());
        assertEquals(List.of(1L), plan.contentGroups().getFirst().articleIds());
        assertEquals(2, plan.issues().size());
    }

    @Test
    void preservesExistingContentGroupForArticleWithoutFullTextInCurrentRun() {
        ClusterArticle first = articleWithContentGroup(
                1L, "삼성전자 HBM4 공급 일정", null,
                FetchStatus.METADATA_ONLY, 10L, hour(0));
        ClusterArticle second = articleWithContentGroup(
                2L, "완전히 다른 제목", null,
                FetchStatus.FETCH_FAILED, 10L, hour(72));

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertEquals(1, plan.issues().size());
        assertEquals(1, plan.issues().getFirst().independentContentCount());
    }

    @Test
    void joinsDifferentTitlesWhenTwoDeterministicEntitiesOverlapWithinWindow() {
        ClusterArticle first = article(
                1L, "삼성전자 차세대 메모리 투자 확대", longBody("삼성전자 HBM4 투자"),
                FetchStatus.FULLTEXT, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "HBM4 생산라인에 추가 장비 투입", longBody("삼성전자 HBM4 생산라인"),
                FetchStatus.FULLTEXT, "매일경제", "0.8", hour(47));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertEquals(1, plan.issues().size());
        assertTrue(plan.pairScores().getFirst().entityOverlap() >= 2);
        assertTrue(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void doesNotUseEntityMatchOutsideFortyEightHourWindow() {
        ClusterArticle first = article(
                1L, "삼성전자 차세대 메모리 투자 확대", longBody("삼성전자 HBM4 투자"),
                FetchStatus.FULLTEXT, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "HBM4 생산라인에 추가 장비 투입", longBody("삼성전자 HBM4 생산라인"),
                FetchStatus.FULLTEXT, "매일경제", "0.8", hour(49));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertEquals(2, plan.issues().size());
        assertFalse(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void joinsWeakTitlePairWhenOrganizationCorroboratesWithinOneDay() {
        ClusterArticle first = article(
                1L, "DGIST 반도체 결함 초음파 검사 기술 공개", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "DGIST 초소형 광 초음파 센서 개발", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(23));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        ClusterPlan.PairScore pair = plan.pairScores().getFirst();
        assertTrue(pair.titleJaccard() < 0.50);
        assertTrue(pair.titleJaccard() >= 0.125);
        assertEquals(1, pair.organizationOverlap());
        assertTrue(pair.sameCluster());
        assertEquals(1, plan.issues().size());
    }

    @Test
    void doesNotUseOrganizationCorroborationOutsideOneDay() {
        ClusterArticle first = article(
                1L, "DGIST 반도체 결함 초음파 검사 기술 공개", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "DGIST 초소형 광 초음파 센서 개발", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(25));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertEquals(2, plan.issues().size());
        assertFalse(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void doesNotTreatSharedProductCodeAsOrganizationCorroboration() {
        ClusterArticle first = article(
                1L, "삼성전자 HBM4 평택 P5 양산 투자 확정", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "SK하이닉스 HBM4 청주 M15X 장비 발주", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(4));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        ClusterPlan.PairScore pair = plan.pairScores().getFirst();
        assertEquals(0, pair.organizationOverlap());
        assertFalse(pair.sameCluster());
        assertEquals(2, plan.issues().size());
    }

    @Test
    void breakingTitleMatchIsLimitedToSixHours() {
        ClusterArticle breaking = article(
                1L, "[속보] 삼성전자 HBM4 증설 발표", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle lateFollowUp = article(
                2L, "삼성전자 HBM4 증설 발표", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.9", hour(7));

        ClusterPlan plan = clusterer.cluster(List.of(breaking, lateFollowUp), true);

        assertEquals(2, plan.issues().size());
        assertFalse(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void preservesSubHourPrecisionInBreakingWindow() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setBreakingTimeWindow(Duration.ofMinutes(90));
        clusterer = new IssueClusterer(properties, new BreakingNewsDetector());
        ClusterArticle breaking = article(
                1L, "[속보] 삼성전자 HBM4 증설 발표", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle followUp = article(
                2L, "삼성전자 HBM4 증설 발표", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.9", hour(1));

        ClusterPlan plan = clusterer.cluster(List.of(breaking, followUp), true);

        assertEquals(1, plan.issues().size());
        assertTrue(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void detailedFollowUpBecomesRepresentativeInsteadOfBreakingArticle() {
        ClusterArticle breaking = article(
                1L, "[속보] 삼성전자 HBM4 증설 발표", "짧은 속보",
                FetchStatus.FULLTEXT, "연합뉴스", "0.95", hour(0));
        ClusterArticle followUp = article(
                2L, "삼성전자 HBM4 증설 발표", longBody("삼성전자 HBM4 증설 발표"),
                FetchStatus.FULLTEXT, "전자신문", "0.80", hour(1));

        ClusterPlan plan = clusterer.cluster(List.of(breaking, followUp));

        assertEquals(1, plan.issues().size());
        assertEquals(2L, plan.issues().getFirst().representativeArticleId());
        assertFalse(plan.issues().getFirst().entities().contains("BREAKING"));
    }

    @Test
    void reusesGlobalContentRepresentativeAcrossTopics() {
        String body = longBody("공동 배포된 동일 보도자료");
        ClusterArticle first = article(
                1L, "반도체 설비 투자 공동 발표", body,
                FetchStatus.FULLTEXT, "전자신문", "0.9", hour(0));
        ClusterArticle second = new ClusterArticle(
                2L,
                8L,
                "제조 자동화 설비 투자 공동 발표",
                "같은 원고",
                body,
                FetchStatus.FULLTEXT,
                2L,
                "산업일보",
                new BigDecimal("0.8"),
                hour(0),
                hour(0),
                List.of("제조"),
                null,
                null,
                null,
                true);

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertEquals(1, plan.contentGroups().size());
        assertEquals(2, plan.issues().size());
        assertTrue(plan.issues().stream().allMatch(issue -> issue.representativeArticleId() == 1L));
        assertTrue(plan.issues().stream()
                .filter(issue -> issue.topicId() == 8L)
                .findFirst()
                .orElseThrow()
                .articleIds()
                .containsAll(List.of(1L, 2L)));
    }

    @Test
    void recordsLosingExistingGroupsAndIssuesForTransactionalMerge() {
        String body = longBody("동일 배포 원고");
        ClusterArticle first = existingArticle(1L, "삼성전자 HBM4 투자 확정", body, 10L, 100L);
        ClusterArticle second = existingArticle(2L, "삼성전자 HBM4 투자 공식 발표", body, 20L, 200L);

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertEquals(1, plan.contentGroups().size());
        assertEquals(10L, plan.contentGroups().getFirst().existingContentGroupId());
        assertEquals(List.of(20L), plan.contentGroups().getFirst().mergedContentGroupIds());
        assertEquals(1, plan.issues().size());
        assertEquals(100L, plan.issues().getFirst().existingIssueId());
        assertEquals(List.of(200L), plan.issues().getFirst().mergedIssueIds());
    }

    @Test
    void usesStableEpochWhenEveryEventTimeIsMissing() {
        ClusterArticle article = new ClusterArticle(
                1L, 7L, "시간 정보 없는 기사", null, null, FetchStatus.METADATA_ONLY,
                1L, "전자신문", new BigDecimal("0.8"), null, null,
                List.of("반도체"), null, null, null, true);

        ClusterPlan plan = clusterer.cluster(List.of(article));

        assertEquals(OffsetDateTime.parse("1970-01-01T00:00:00Z"),
                plan.issues().getFirst().firstSeenAt());
        assertTrue(plan.pairScores().isEmpty());
    }

    @Test
    void keepsPairDiagnosticsOptIn() {
        ClusterArticle first = article(
                1L, "삼성전자 HBM4 투자", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "삼성전자 HBM4 공식 투자", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(1));

        assertTrue(clusterer.cluster(List.of(first, second)).pairScores().isEmpty());
        assertEquals(1, clusterer.cluster(List.of(first, second), true).pairScores().size());
    }

    private ClusterArticle existingArticle(long id,
                                           String title,
                                           String body,
                                           long contentGroupId,
                                           long issueId) {
        return new ClusterArticle(
                id, 7L, title, title, body, FetchStatus.FULLTEXT, id,
                "매체" + id, new BigDecimal("0.9"), hour(0), hour(0),
                List.of("반도체"), contentGroupId, SimHash.toHex(SimHash.of(body)), issueId, true);
    }

    private ClusterArticle article(long id,
                                   String title,
                                   String body,
                                   FetchStatus fetchStatus,
                                   String publisher,
                                   String reliability,
                                   OffsetDateTime publishedAt) {
        return article(id, title, body, fetchStatus, publisher, reliability, publishedAt, null);
    }

    private ClusterArticle article(long id,
                                   String title,
                                   String body,
                                   FetchStatus fetchStatus,
                                   String publisher,
                                   String reliability,
                                   OffsetDateTime publishedAt,
                                   Long contentGroupId) {
        return new ClusterArticle(
                id,
                7L,
                title,
                title + " 관련 상세 보도",
                body,
                fetchStatus,
                id,
                publisher,
                new BigDecimal(reliability),
                publishedAt,
                publishedAt,
                List.of("반도체"),
                contentGroupId,
                contentGroupId == null ? null : body == null
                        ? "0000000000000000" : SimHash.toHex(SimHash.of(body)),
                null,
                true);
    }

    private ClusterArticle articleWithContentGroup(long id,
                                                   String title,
                                                   String body,
                                                   long contentGroupId,
                                                   OffsetDateTime publishedAt) {
        return articleWithContentGroup(
                id, title, body, FetchStatus.FULLTEXT, contentGroupId, publishedAt);
    }

    private ClusterArticle articleWithContentGroup(long id,
                                                   String title,
                                                   String body,
                                                   FetchStatus fetchStatus,
                                                   long contentGroupId,
                                                   OffsetDateTime publishedAt) {
        return article(
                id, title, body, fetchStatus, "뉴시스", "0.8", publishedAt, contentGroupId);
    }

    private String longBody(String sentence) {
        return (sentence + ". 관련 업계와 공급망의 구체적인 일정과 생산 계획을 설명했다. ")
                .repeat(20);
    }

    private OffsetDateTime hour(int hours) {
        return OffsetDateTime.parse("2026-08-10T00:00:00+09:00").plusHours(hours);
    }

    /**
     * 주제 어휘로 전체가 한 이슈가 되는 것을 막는다 (#118).
     *
     * <p>반도체 주제의 기사는 거의 다 {@code HBM4}·{@code DDR5}를 언급한다. 그것만 공유하는
     * 서로 다른 사건 24건이 교집합 2 규칙으로 한 덩어리가 되던 것이 실수집 과병합의 정체다.
     * 실행 안에서 문서빈도를 세어 흔한 말을 빼면 각 사건이 따로 남는다.
     */
    @Test
    void doesNotMergeEverythingThatSharesTopicVocabulary() {
        // 제목이 서로 겹치지 않는 별개 사건들이다. 공유하는 것은 본문의 주제 어휘 둘(HBM4·DDR5)뿐.
        List<String> titles = List.of(
                "롯데 주력 계열사 영업익 일제 반등", "노란봉투법 해석지침 보완 임박",
                "대한화섬 고성능 섬유 상업 생산 개시", "7월 산업생산 제자리걸음",
                "가톨릭관동대 피지컬 포럼 개최", "유럽증시 무력충돌 여파로 하락",
                "네이버 컬리 거래액 1년 새 급증", "넥슨 신작 게임 출시일 확정",
                "조선업 수주 잔량 역대 최대", "은행권 가계대출 증가폭 둔화",
                "제주 항공편 결항 속출", "전기차 보조금 지급 기준 개편",
                "프로야구 관중 신기록 경신", "서울 아파트 전세가율 상승",
                "농산물 도매가격 급등세", "해운 운임 지수 반락",
                "철강 수출 관세 협상 난항", "바이오 위탁생산 계약 체결",
                "카드사 연체율 소폭 상승", "면세점 매출 회복 조짐",
                "택배 물동량 추석 앞두고 증가", "건설 수주액 전년비 감소",
                "국제 유가 배럴당 등락", "통신 3사 요금제 개편 검토");
        List<ClusterArticle> articles = new java.util.ArrayList<>();
        for (int index = 0; index < titles.size(); index++) {
            articles.add(article(
                    index + 1L,
                    titles.get(index),
                    longBody("HBM4 DDR5 시장 상황과 " + titles.get(index)),
                    FetchStatus.FULLTEXT, "매체" + index, "0.8", hour(index % 40)));
        }

        ClusterPlan plan = clusterer.cluster(articles);

        assertTrue(plan.issues().size() >= 20,
                "주제 어휘만 공유하는 사건 24건이 이슈 %d개로 뭉쳤다".formatted(plan.issues().size()));
    }

    /** 표본이 작으면 문서빈도 컷을 적용하지 않는다. 비율이 의미를 갖지 못한다. */
    @Test
    void keepsEntityMatchingWhenSampleIsTooSmallForDocumentFrequency() {
        ClusterArticle first = article(
                1L, "삼성전자 차세대 메모리 투자 확대", longBody("삼성전자 HBM4 투자"),
                FetchStatus.FULLTEXT, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "HBM4 생산라인에 추가 장비 투입", longBody("삼성전자 HBM4 생산라인"),
                FetchStatus.FULLTEXT, "매일경제", "0.8", hour(47));

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertEquals(1, plan.issues().size());
    }

    /** 최소 표본 20건에서도 한 쌍만 공유하는 엔티티는 흔한 주제 어휘로 제거하지 않는다. */
    @Test
    void keepsPairOnlyEntitiesAtMinimumDocumentFrequencySample() {
        List<String> unrelatedTitles = List.of(
                "롯데 주력 계열사 영업익 일제 반등", "노란봉투법 해석지침 보완 임박",
                "대한화섬 고성능 섬유 상업 생산 개시", "7월 산업생산 제자리걸음",
                "가톨릭관동대 피지컬 포럼 개최", "유럽증시 무력충돌 여파로 하락",
                "네이버 컬리 거래액 1년 새 급증", "넥슨 신작 게임 출시일 확정",
                "조선업 수주 잔량 역대 최대", "은행권 가계대출 증가폭 둔화",
                "제주 항공편 결항 속출", "전기차 보조금 지급 기준 개편",
                "프로야구 관중 신기록 경신", "서울 아파트 전세가율 상승",
                "농산물 도매가격 급등세", "해운 운임 지수 반락",
                "철강 수출 관세 협상 난항", "바이오 위탁생산 계약 체결");
        List<ClusterArticle> articles = new java.util.ArrayList<>();
        articles.add(article(
                1L, "오로라 가속기 MI300X CDNA4 공급 계약",
                null, FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0)));
        articles.add(article(
                2L, "데이터센터 MI300X CDNA4 생산 일정 공개",
                null, FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(1)));
        for (int index = 0; index < unrelatedTitles.size(); index++) {
            articles.add(article(
                    index + 3L, unrelatedTitles.get(index), null,
                    FetchStatus.METADATA_ONLY, "매체" + index, "0.8", hour(index + 2)));
        }

        ClusterPlan plan = clusterer.cluster(articles, true);

        assertEquals(19, plan.issues().size(), "관련 기사 한 쌍만 합쳐져야 한다");
        ClusterPlan.PairScore relatedPair = plan.pairScores().stream()
                .filter(score -> score.leftArticleId() == 1L && score.rightArticleId() == 2L)
                .findFirst()
                .orElseThrow();
        assertEquals(2, relatedPair.entityOverlap());
        assertTrue(relatedPair.sameCluster());
    }
}
