package com.example.be.domain.reports.repository;

import com.example.be.domain.reports.entity.NewsReport;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

public final class NewsReportSpecification {

    private NewsReportSpecification() {
    }

    public static Specification<NewsReport> generatedBetween(LocalDateTime from, LocalDateTime to) {
        return (root, query, builder) -> {
            var predicate = builder.conjunction();
            if (from != null) {
                predicate = builder.and(predicate, builder.greaterThanOrEqualTo(root.get("generatedAt"), from));
            }
            if (to != null) {
                predicate = builder.and(predicate, builder.lessThanOrEqualTo(root.get("generatedAt"), to));
            }
            return predicate;
        };
    }
}
