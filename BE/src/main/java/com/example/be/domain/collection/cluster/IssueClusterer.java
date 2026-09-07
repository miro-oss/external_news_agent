package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 제목·결정론적 엔티티·본문 지문만 쓰는 Spring 내부 클러스터러. LLM과 DB를 호출하지 않는다. */
@Component
@RequiredArgsConstructor
public class IssueClusterer {

    static final String RULE_VERSION = "event-text-evidence-v2";

    private static final OffsetDateTime UNKNOWN_EVENT_TIME = OffsetDateTime.parse("1970-01-01T00:00:00Z");
    /** 조직 하나가 대형 주제 전체를 다시 연결하지 못하게 하되 소규모 보도자료 사건군은 남긴다. */
    private static final int MAX_COMMON_ORGANIZATION_DOCUMENT_FREQUENCY = 8;

    private final IssueClusteringProperties properties;
    private final BreakingNewsDetector breakingNewsDetector;
    private final DeterministicEntityExtractor entityExtractor = new DeterministicEntityExtractor();

    public ClusterPlan cluster(List<ClusterArticle> rawArticles) {
        return cluster(rawArticles, false);
    }

    /** 클러스터링과 오프라인 export가 같은 인스턴스의 전처리·추출기 설정을 공유한다. */
    Set<String> titleOrganizations(String title) {
        return entityExtractor.extractTitleOrganizations(breakingNewsDetector.coreTitle(title));
    }

    /** pair score는 오프라인 측정 전용이다. 프로덕션에서는 O(n²) 진단 목록을 보관하지 않는다. */
    public ClusterPlan cluster(List<ClusterArticle> rawArticles, boolean includePairScores) {
        List<ClusterArticle> articles = deduplicate(rawArticles);
        if (articles.isEmpty()) {
            return new ClusterPlan(List.of(), List.of(), List.of());
        }

        ContentGrouping contentGrouping = contentGroups(articles);
        List<ClusterPlan.PairScore> pairScores = new ArrayList<>();
        List<ClusterPlan.IssueAssignment> issues = new ArrayList<>();
        articles.stream().collect(Collectors.groupingBy(
                        ClusterArticle::topicId, LinkedHashMap::new, Collectors.toList()))
                .forEach((topicId, topicArticles) -> issues.addAll(
                        clusterTopic(topicId, topicArticles, contentGrouping, pairScores, includePairScores)));

        return new ClusterPlan(contentGrouping.assignments(), issues, pairScores);
    }

    private List<ClusterArticle> deduplicate(List<ClusterArticle> articles) {
        Map<ArticleTopicKey, ClusterArticle> unique = new LinkedHashMap<>();
        for (ClusterArticle article : articles) {
            ArticleTopicKey key = new ArticleTopicKey(article.articleId(), article.topicId());
            unique.merge(key, article, (left, right) -> right.observedInRun() ? right : left);
        }
        return List.copyOf(unique.values());
    }

    private ContentGrouping contentGroups(List<ClusterArticle> articles) {
        Map<Long, ClusterArticle> fullTextById = new LinkedHashMap<>();
        Map<Long, Long> fingerprintByArticle = new LinkedHashMap<>();
        Set<Long> rejectedFullTextIds = new HashSet<>();
        for (ClusterArticle article : articles) {
            if (!article.hasFullText() || fullTextById.containsKey(article.articleId())) {
                continue;
            }
            SimHash.tryOfArticleBody(article.body(), properties.getMinArticleContentLength())
                    .ifPresentOrElse(fingerprint -> {
                        fullTextById.put(article.articleId(), article);
                        fingerprintByArticle.put(article.articleId(), fingerprint);
                    }, () -> rejectedFullTextIds.add(article.articleId()));
        }
        List<ClusterArticle> fullText = List.copyOf(fullTextById.values());
        UnionFind union = new UnionFind(fullText.stream().map(ClusterArticle::articleId).toList());

        for (int left = 0; left < fullText.size(); left++) {
            for (int right = left + 1; right < fullText.size(); right++) {
                ClusterArticle first = fullText.get(left);
                ClusterArticle second = fullText.get(right);
                boolean alreadyGrouped = first.contentGroupId() != null
                        && first.contentGroupId().equals(second.contentGroupId());
                int distance = SimHash.distance(
                        fingerprintByArticle.get(first.articleId()),
                        fingerprintByArticle.get(second.articleId()));
                if (alreadyGrouped || distance <= properties.getSimhashHammingThreshold()) {
                    union.join(first.articleId(), second.articleId());
                }
            }
        }

        Map<Long, List<ClusterArticle>> components = fullText.stream()
                .collect(Collectors.groupingBy(
                        article -> union.root(article.articleId()), LinkedHashMap::new, Collectors.toList()));
        List<ClusterPlan.ContentGroupAssignment> assignments = new ArrayList<>();
        Map<Long, String> contentKeyByArticle = new HashMap<>();
        Map<Long, Long> representativeByArticle = new HashMap<>();

        for (List<ClusterArticle> component : components.values()) {
            List<Long> existingIds = component.stream()
                    .map(ClusterArticle::contentGroupId)
                    .filter(value -> value != null)
                    .distinct()
                    .sorted()
                    .toList();
            if (component.size() < 2 && existingIds.isEmpty()) {
                ClusterArticle only = component.getFirst();
                contentKeyByArticle.put(only.articleId(), "article:" + only.articleId());
                representativeByArticle.put(only.articleId(), only.articleId());
                continue;
            }

            ClusterArticle representative = representative(component);
            Long existingId = existingIds.isEmpty() ? null : existingIds.getFirst();
            String key = existingId == null
                    ? "new-group:" + representative.articleId()
                    : "content-group:" + existingId;
            List<Long> articleIds = component.stream().map(ClusterArticle::articleId).sorted().toList();
            assignments.add(new ClusterPlan.ContentGroupAssignment(
                    existingId,
                    existingIds.stream().filter(id -> !id.equals(existingId)).toList(),
                    representative.articleId(),
                    SimHash.toHex(fingerprintByArticle.get(representative.articleId())),
                    articleIds));
            articleIds.forEach(articleId -> {
                contentKeyByArticle.put(articleId, key);
                representativeByArticle.put(articleId, representative.articleId());
            });
        }

        articles.forEach(article -> {
            contentKeyByArticle.putIfAbsent(article.articleId(),
                    article.contentGroupId() == null || rejectedFullTextIds.contains(article.articleId())
                            ? "article:" + article.articleId()
                            : "content-group:" + article.contentGroupId());
            representativeByArticle.putIfAbsent(article.articleId(), article.articleId());
        });
        Map<Long, ClusterArticle> articleById = articles.stream().collect(Collectors.toMap(
                ClusterArticle::articleId, Function.identity(), (left, right) -> left));
        return new ContentGrouping(
                List.copyOf(assignments), contentKeyByArticle, representativeByArticle, articleById);
    }

    private List<ClusterPlan.IssueAssignment> clusterTopic(long topicId,
                                                            List<ClusterArticle> articles,
                                                            ContentGrouping contentGrouping,
                                                            List<ClusterPlan.PairScore> pairScores,
                                                            boolean includePairScores) {
        Map<Long, ClusterArticle> byId = articles.stream().collect(Collectors.toMap(
                ClusterArticle::articleId,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new));
        // 같은 본문 그룹의 전역 대표가 다른 주제에서 관측됐더라도 이 주제 이슈에도 provenance로
        // 연결한다. 그러면 대표 본문은 한 번만 분석되고 그 finding이 두 이슈 요약을 갱신한다.
        List<ClusterArticle> localArticles = List.copyOf(byId.values());
        for (ClusterArticle proxy : localArticles) {
            long representativeId = contentGrouping.representativeByArticle().get(proxy.articleId());
            if (!byId.containsKey(representativeId)) {
                ClusterArticle globalRepresentative = contentGrouping.articleById().get(representativeId);
                byId.put(representativeId, forTopic(globalRepresentative, proxy));
            }
        }
        List<ClusterArticle> unique = List.copyOf(byId.values());

        Map<Long, Set<String>> titleTokens = new HashMap<>();
        Map<Long, Set<String>> entities = new HashMap<>();
        Map<Long, Set<String>> organizations = new HashMap<>();
        Map<Long, Set<String>> titleOrganizations = new HashMap<>();
        Map<Long, Set<String>> titleEntities = new HashMap<>();
        unique.forEach(article -> {
            String coreTitle = breakingNewsDetector.coreTitle(article.title());
            titleTokens.put(article.articleId(), TitleTokenizer.tokens(coreTitle));
            titleOrganizations.put(article.articleId(), titleOrganizations(article.title()));
            titleEntities.put(article.articleId(), entityExtractor.extract(coreTitle, null, null, List.of()));
            DeterministicEntityExtractor.Extraction extraction = entityExtractor.extractWithOrganizations(
                    coreTitle, article.summary(),
                    ArticleBodyCleaner.withoutTrailingBoilerplate(article.body()),
                    article.topicKeywords());
            organizations.put(article.articleId(), extraction.organizations());
            entities.put(article.articleId(), extraction.entities());
        });

        UnionFind union = new UnionFind(byId.keySet(), titleOrganizations);
        // 저장된 멤버십과 동일 본문은 보존한다. 새 규칙 간선에만 조직 충돌 방어를 적용한다.
        // 이미 업체가 섞인 그룹도 프로파일을 보존한다. 한 업체의 후속 기사는 다른 기존 업체와
        // 충돌하므로 별도 이슈로 남긴다. 과거 과병합을 늘리면서 정상화한 것으로 취급하지 않는다.
        Map<Long, List<ClusterArticle>> byExistingIssue = unique.stream()
                .filter(article -> article.existingIssueId() != null)
                .collect(Collectors.groupingBy(ClusterArticle::existingIssueId));
        byExistingIssue.values().forEach(component -> joinAll(union, component));

        Map<String, List<ClusterArticle>> byContent = unique.stream()
                .collect(Collectors.groupingBy(
                        article -> contentGrouping.contentKeyByArticle().get(article.articleId())));
        byContent.values().forEach(component -> joinAll(union, component));

        // 같은 본문 중복군에서는 대표만 사건 유사도 투표에 참여한다.
        List<ClusterArticle> voting = unique.stream()
                .filter(article -> contentGrouping.representativeByArticle().get(article.articleId())
                        .equals(article.articleId()))
                .sorted(Comparator.comparingLong(ClusterArticle::articleId))
                .toList();
        Set<String> commonEntities = commonEntities(voting, entities);
        Set<String> commonOrganizations = commonOrganizations(voting, organizations);
        EventTextEvidence eventEvidence = new EventTextEvidence(voting, breakingNewsDetector);
        for (int left = 0; left < voting.size(); left++) {
            for (int right = left + 1; right < voting.size(); right++) {
                ClusterArticle first = voting.get(left);
                ClusterArticle second = voting.get(right);
                double jaccard = jaccard(
                        titleTokens.get(first.articleId()), titleTokens.get(second.articleId()));
                int entityOverlap = discriminativeOverlap(
                        entities.get(first.articleId()),
                        entities.get(second.articleId()),
                        commonEntities);
                int organizationOverlap = discriminativeOverlap(
                        organizations.get(first.articleId()),
                        organizations.get(second.articleId()),
                        commonOrganizations);
                double hoursApart = hoursApart(first.eventTime(), second.eventTime());
                boolean breakingPair = breakingNewsDetector.isBreaking(first)
                        || breakingNewsDetector.isBreaking(second);
                boolean titleMatches = jaccard >= properties.getTitleJaccardThreshold();
                boolean entityTitleSupported = discriminativeOverlap(
                        titleEntities.get(first.articleId()), titleEntities.get(second.articleId()),
                        commonEntities) >= 1;
                Set<String> pairTitleOrganizations = new HashSet<>(titleOrganizations.get(first.articleId()));
                pairTitleOrganizations.addAll(titleOrganizations.get(second.articleId()));
                EventTextEvidence.Evidence evidence = eventEvidence.compare(
                        first.articleId(), second.articleId(), pairTitleOrganizations);
                boolean enoughEntities = entityOverlap >= properties.getEntityOverlapThreshold()
                        && entityTitleSupported;
                boolean organizationTitleMatches = organizationOverlap >= 1
                        && jaccard >= properties.getOrganizationTitleJaccardThreshold()
                        && evidence.organizationSupported();
                boolean lexicalMatch = evidence.eventMatch() && within(
                        first.eventTime(), second.eventTime(), breakingPair
                                ? properties.getBreakingTimeWindow() : properties.getOrganizationTimeWindow());
                boolean matches = (matchesIssue(
                        first, second, breakingPair,
                        titleMatches, enoughEntities, organizationTitleMatches) || lexicalMatch)
                        && union.canJoin(first.articleId(), second.articleId());
                if (matches) {
                    union.join(first.articleId(), second.articleId());
                }
                if (includePairScores) {
                    pairScores.add(new ClusterPlan.PairScore(
                            first.articleId(), second.articleId(), topicId,
                            jaccard, entityOverlap, organizationOverlap,
                            breakingPair, hoursApart, evidence.titleSimilarity(), evidence.leadSimilarity(),
                            evidence.eventMatch(), entityTitleSupported, evidence.organizationSupported(), matches));
                }
            }
        }

        Map<Long, List<ClusterArticle>> components = unique.stream()
                .collect(Collectors.groupingBy(
                        article -> union.root(article.articleId()), LinkedHashMap::new, Collectors.toList()));
        List<ClusterPlan.IssueAssignment> assignments = new ArrayList<>();
        for (List<ClusterArticle> component : components.values()) {
            ClusterArticle representative = representative(component.stream()
                    .filter(article -> contentGrouping.representativeByArticle().get(article.articleId())
                            .equals(article.articleId()))
                    .toList());
            List<Long> existingIssueIds = component.stream()
                    .map(ClusterArticle::existingIssueId)
                    .filter(value -> value != null)
                    .distinct()
                    .sorted()
                    .toList();
            Long existingIssueId = existingIssueIds.isEmpty() ? null : existingIssueIds.getFirst();
            List<Long> memberIds = component.stream().map(ClusterArticle::articleId).sorted().toList();
            List<String> combinedEntities = component.stream()
                    .flatMap(article -> entities.get(article.articleId()).stream())
                    .distinct()
                    .sorted()
                    .toList();
            OffsetDateTime firstSeen = component.stream()
                    .map(ClusterArticle::eventTime)
                    .filter(value -> value != null)
                    .min(OffsetDateTime::compareTo)
                    .orElse(UNKNOWN_EVENT_TIME);
            OffsetDateTime lastSeen = component.stream()
                    .map(ClusterArticle::eventTime)
                    .filter(value -> value != null)
                    .max(OffsetDateTime::compareTo)
                    .orElse(firstSeen);
            int publisherCount = (int) component.stream()
                    .map(ClusterArticle::publisher)
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase())
                    .distinct()
                    .count();
            int independentContentCount = (int) component.stream()
                    .map(article -> contentGrouping.contentKeyByArticle().get(article.articleId()))
                    .distinct()
                    .count();
            assignments.add(new ClusterPlan.IssueAssignment(
                    existingIssueId,
                    existingIssueIds.stream().filter(id -> !id.equals(existingIssueId)).toList(),
                    topicId,
                    representative.articleId(),
                    memberIds,
                    combinedEntities,
                    firstSeen,
                    lastSeen,
                    publisherCount,
                    independentContentCount));
        }
        return List.copyOf(assignments);
    }

    private ClusterArticle representative(Collection<ClusterArticle> articles) {
        return articles.stream().min(Comparator
                        .comparing(breakingNewsDetector::isBreaking)
                        .thenComparing(article -> !hasSubstantialBody(article))
                        .thenComparing(ClusterArticle::reliabilityScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ClusterArticle::eventTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingLong(ClusterArticle::articleId))
                .orElseThrow();
    }

    private boolean hasSubstantialBody(ClusterArticle article) {
        return article.hasFullText()
                && ArticleBodyCleaner.withoutTrailingBoilerplate(article.body()).length() >= 500;
    }

    private void joinAll(UnionFind union, List<ClusterArticle> articles) {
        if (articles.size() < 2) {
            return;
        }
        long first = articles.getFirst().articleId();
        articles.stream().skip(1).forEach(article -> union.join(first, article.articleId()));
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private boolean matchesIssue(ClusterArticle first,
                                 ClusterArticle second,
                                 boolean breakingPair,
                                 boolean titleMatches,
                                 boolean enoughEntities,
                                 boolean organizationTitleMatches) {
        if (breakingPair) {
            return within(first.eventTime(), second.eventTime(), properties.getBreakingTimeWindow())
                    && (titleMatches || enoughEntities || organizationTitleMatches);
        }
        return titleMatches
                || (enoughEntities
                && within(first.eventTime(), second.eventTime(), properties.getEntityTimeWindow()))
                || (organizationTitleMatches
                && within(first.eventTime(), second.eventTime(), properties.getOrganizationTimeWindow()));
    }

    /**
     * 이번 실행에서 이 주제의 기사 상당수에 나타나는 엔티티를 고른다. 사건이 아니라 주제를 가리키는 말이다.
     *
     * <p>반도체 주제에서 {@code HBM}·{@code GPU}·{@code 삼성전자}가 그렇다. 이런 걸 2개 공유했다고
     * 같은 사건으로 묶으면 주제 전체가 한 이슈가 된다 (#118). 실행 안에서만 세므로 IDF 테이블도
     * DB 조회도 필요 없고, 같은 입력이면 같은 결과라 결정론이 깨지지 않는다.
     *
     * <p>표본이 작으면 비율이 의미를 갖지 못하므로 기사 수가 기준 미만이면 아무것도 빼지 않는다.
     */
    private Set<String> commonEntities(List<ClusterArticle> voting, Map<Long, Set<String>> entities) {
        return EntityDocumentFrequencyFilter.commonValues(
                voting.stream()
                        .map(article -> entities.getOrDefault(article.articleId(), Set.of()))
                        .toList(),
                properties.getCommonEntityMinArticles(),
                EntityDocumentFrequencyFilter.MIN_COMMON_ENTITY_DOCUMENT_FREQUENCY,
                properties.getCommonEntityDocumentRatio());
    }

    /** 한 조직이 주제 전체의 보조 간선을 만들지 않도록 조직 전용의 문서빈도 상한을 둔다. */
    private Set<String> commonOrganizations(List<ClusterArticle> voting,
                                            Map<Long, Set<String>> organizations) {
        return EntityDocumentFrequencyFilter.commonValues(
                voting.stream()
                        .map(article -> organizations.getOrDefault(article.articleId(), Set.of()))
                        .toList(),
                properties.getCommonEntityMinArticles(),
                MAX_COMMON_ORGANIZATION_DOCUMENT_FREQUENCY);
    }

    /** 주제 어휘를 뺀 뒤 남는 교집합만 사건 신호로 센다. */
    private int discriminativeOverlap(Set<String> left, Set<String> right, Set<String> common) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        intersection.removeAll(common);
        return intersection.size();
    }

    private double hoursApart(OffsetDateTime left, OffsetDateTime right) {
        if (left == null || right == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Duration.between(left, right).abs().toMillis() / 3_600_000.0;
    }

    private boolean within(OffsetDateTime left, OffsetDateTime right, Duration window) {
        return left != null && right != null
                && Duration.between(left, right).abs().compareTo(window) <= 0;
    }

    private ClusterArticle forTopic(ClusterArticle representative, ClusterArticle proxy) {
        return new ClusterArticle(
                representative.articleId(),
                proxy.topicId(),
                representative.title(),
                representative.summary(),
                representative.body(),
                representative.fetchStatus(),
                representative.sourceId(),
                representative.publisher(),
                representative.reliabilityScore(),
                representative.publishedAt(),
                representative.observedAt(),
                proxy.topicKeywords(),
                representative.contentGroupId(),
                representative.contentGroupSimhash(),
                null,
                false);
    }

    private record ArticleTopicKey(long articleId, long topicId) {
    }

    private record ContentGrouping(
            List<ClusterPlan.ContentGroupAssignment> assignments,
            Map<Long, String> contentKeyByArticle,
            Map<Long, Long> representativeByArticle,
            Map<Long, ClusterArticle> articleById
    ) {
    }

    private static final class UnionFind {

        private final Map<Long, Long> parents = new HashMap<>();
        private final Map<Long, Set<Set<String>>> organizationProfiles = new HashMap<>();

        private UnionFind(Collection<Long> values) {
            this(values, Map.of());
        }

        private UnionFind(Collection<Long> values, Map<Long, Set<String>> titleOrganizations) {
            values.forEach(value -> {
                parents.put(value, value);
                Set<Set<String>> profiles = new HashSet<>();
                Set<String> organizations = titleOrganizations.getOrDefault(value, Set.of());
                if (!organizations.isEmpty()) {
                    profiles.add(Set.copyOf(organizations));
                }
                organizationProfiles.put(value, profiles);
            });
        }

        private long root(long value) {
            long parent = parents.getOrDefault(value, value);
            if (parent == value) {
                parents.putIfAbsent(value, value);
                return value;
            }
            long root = root(parent);
            parents.put(value, root);
            return root;
        }

        /** 조직 미상·복수 조직 기사가 서로 다른 업체 사이에 전이 경로를 만들지 못하게 한다. */
        private boolean canJoin(long left, long right) {
            long leftRoot = root(left);
            long rightRoot = root(right);
            if (leftRoot == rightRoot) {
                return true;
            }
            for (Set<String> leftProfile : organizationProfiles.get(leftRoot)) {
                for (Set<String> rightProfile : organizationProfiles.get(rightRoot)) {
                    if (Collections.disjoint(leftProfile, rightProfile)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void join(long left, long right) {
            long leftRoot = root(left);
            long rightRoot = root(right);
            if (leftRoot != rightRoot) {
                long retained = Math.min(leftRoot, rightRoot);
                long removed = Math.max(leftRoot, rightRoot);
                parents.put(removed, retained);
                organizationProfiles.get(retained).addAll(organizationProfiles.remove(removed));
            }
        }
    }
}
