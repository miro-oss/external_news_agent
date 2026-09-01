package com.example.be.domain.articles.repository;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.AudienceRelevance;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class FindingSpecification {

    public static final String SORT_PUBLISHED_DESC = "PUBLISHED_DESC";
    public static final String SORT_PUBLISHED_ASC = "PUBLISHED_ASC";
    public static final String SORT_SENSITIVITY_DESC = "SENSITIVITY_DESC";

    private FindingSpecification() {
    }

    public static Specification<Finding> latestWithFilters(Long runId,
                                                            Long topicId,
                                                            Long sourceId,
                                                            ChangeType changeType,
                                                            Relevance relevance,
                                                            SensitivityLevel sensitivityLevel,
                                                            String category,
                                                            String language,
                                                            Audience audience,
                                                            AudienceRelevance minAudienceRelevance,
                                                            OffsetDateTime from,
                                                            OffsetDateTime to,
                                                            String sort,
                                                            BigDecimal mediumThreshold,
                                                            BigDecimal highThreshold) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // runId를 생략하면 기사별 최신 분석만 보인다. 같은 기사의 과거 분석 때문에 목록이 중복되지 않는다.
            if (runId == null) {
                Subquery<Long> latest = query.subquery(Long.class);
                Root<Finding> candidate = latest.from(Finding.class);
                latest.select(builder.max(candidate.get("id")))
                        .where(builder.equal(candidate.get("article"), root.get("article")));
                predicates.add(builder.equal(root.get("id"), latest));
            } else {
                predicates.add(builder.equal(root.get("run").get("id"), runId));
            }

            if (topicId != null || sourceId != null) {
                Subquery<Long> observed = query.subquery(Long.class);
                Root<CollectionRunArticle> observation = observed.from(CollectionRunArticle.class);
                List<Predicate> observationPredicates = new ArrayList<>();
                // 주제·소스는 최신 finding 실행이 아니라 기사의 전체 수집 이력에 대해 필터링한다.
                observationPredicates.add(builder.equal(observation.get("article"), root.get("article")));
                if (topicId != null) {
                    observationPredicates.add(builder.equal(observation.get("topic").get("id"), topicId));
                }
                if (sourceId != null) {
                    observationPredicates.add(builder.equal(observation.get("source").get("id"), sourceId));
                }
                observed.select(observation.get("id"))
                        .where(observationPredicates.toArray(Predicate[]::new));
                predicates.add(builder.exists(observed));
            }

            addEqual(predicates, builder, root, "changeType", changeType);
            addEqual(predicates, builder, root, "relevance", relevance);
            addSensitivityLevel(predicates, builder, root, sensitivityLevel,
                    mediumThreshold, highThreshold);
            addEqual(predicates, builder, root, "category", category);
            if (language != null) {
                predicates.add(builder.equal(
                        builder.lower(root.get("article").get("language")),
                        language.toLowerCase(Locale.ROOT)));
            }
            if (audience != null && minAudienceRelevance != null) {
                Expression<Boolean> matchesAudience = builder.function(
                        "json_exists",
                        Boolean.class,
                        root.get("perspectiveTags"),
                        builder.literal(audiencePath(audience, minAudienceRelevance)));
                predicates.add(builder.isTrue(matchesAudience));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("article").get("publishedAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("article").get("publishedAt"), to));
            }

            applyOrder(query, builder, root, sort);
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String audiencePath(Audience audience, AudienceRelevance minimum) {
        String relevances = Arrays.stream(AudienceRelevance.values())
                .filter(value -> value.isAtLeast(minimum))
                .map(value -> "@.relevance == \"" + value.toApiValue() + "\"")
                .collect(Collectors.joining(" || "));
        return "$[*]?(@.audience == \"" + audience.name() + "\" && (" + relevances + "))";
    }

    private static void addEqual(List<Predicate> predicates,
                                 jakarta.persistence.criteria.CriteriaBuilder builder,
                                 Root<Finding> root,
                                 String field,
                                 Object value) {
        if (value != null) {
            predicates.add(builder.equal(root.get(field), value));
        }
    }

    private static void addSensitivityLevel(List<Predicate> predicates,
                                            jakarta.persistence.criteria.CriteriaBuilder builder,
                                            Root<Finding> root,
                                            SensitivityLevel level,
                                            BigDecimal mediumThreshold,
                                            BigDecimal highThreshold) {
        if (level == null) {
            return;
        }
        Expression<java.math.BigDecimal> score = root.get("sensitivity").get("score");
        switch (level) {
            case HIGH -> predicates.add(builder.greaterThanOrEqualTo(score, highThreshold));
            case MEDIUM -> predicates.add(builder.and(
                    builder.greaterThanOrEqualTo(score, mediumThreshold),
                    builder.lessThan(score, highThreshold)));
            case LOW -> predicates.add(builder.lessThan(score, mediumThreshold));
        }
    }

    private static void applyOrder(jakarta.persistence.criteria.CriteriaQuery<?> query,
                                   jakarta.persistence.criteria.CriteriaBuilder builder,
                                   Root<Finding> root,
                                   String sort) {
        if (Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType())) {
            return;
        }

        Expression<OffsetDateTime> publishedAt = root.get("article").get("publishedAt");
        if (SORT_PUBLISHED_ASC.equals(sort)) {
            query.orderBy(builder.asc(publishedAt), builder.asc(root.get("id")));
            return;
        }
        if (SORT_SENSITIVITY_DESC.equals(sort)) {
            query.orderBy(builder.desc(root.get("sensitivity").get("score")),
                    builder.desc(publishedAt), builder.desc(root.get("id")));
            return;
        }
        query.orderBy(builder.desc(publishedAt), builder.desc(root.get("id")));
    }
}
