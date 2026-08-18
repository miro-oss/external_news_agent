package com.example.be.domain.articles.repository;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class FindingSpecification {

    public static final String SORT_PUBLISHED_DESC = "PUBLISHED_DESC";
    public static final String SORT_PUBLISHED_ASC = "PUBLISHED_ASC";
    public static final String SORT_RISK_DESC = "RISK_DESC";

    private FindingSpecification() {
    }

    public static Specification<Finding> latestWithFilters(Long runId,
                                                            Long topicId,
                                                            Long sourceId,
                                                            ChangeType changeType,
                                                            Relevance relevance,
                                                            RiskLevel riskLevel,
                                                            String category,
                                                            String language,
                                                            OffsetDateTime from,
                                                            OffsetDateTime to,
                                                            String sort) {
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
                observationPredicates.add(builder.equal(observation.get("article"), root.get("article")));
                observationPredicates.add(builder.equal(observation.get("run"), root.get("run")));
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
            addEqual(predicates, builder, root, "riskLevel", riskLevel);
            addEqual(predicates, builder, root, "category", category);
            if (language != null) {
                predicates.add(builder.equal(builder.lower(root.get("article").get("language")), language.toLowerCase()));
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

    private static void addEqual(List<Predicate> predicates,
                                 jakarta.persistence.criteria.CriteriaBuilder builder,
                                 Root<Finding> root,
                                 String field,
                                 Object value) {
        if (value != null) {
            predicates.add(builder.equal(root.get(field), value));
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
        if (SORT_RISK_DESC.equals(sort)) {
            Expression<Integer> riskOrder = builder.<Integer>selectCase()
                    .when(builder.equal(root.get("riskLevel"), RiskLevel.HIGH), 3)
                    .when(builder.equal(root.get("riskLevel"), RiskLevel.MEDIUM), 2)
                    .otherwise(1);
            query.orderBy(builder.desc(riskOrder), builder.desc(publishedAt), builder.desc(root.get("id")));
            return;
        }
        query.orderBy(builder.desc(publishedAt), builder.desc(root.get("id")));
    }
}
