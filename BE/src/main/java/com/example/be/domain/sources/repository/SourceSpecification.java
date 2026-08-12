package com.example.be.domain.sources.repository;

import com.example.be.domain.sources.entity.Source;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SourceSpecification {

    private static final char LIKE_ESCAPE = '\\';

    private SourceSpecification() {
    }

    public static Specification<Source> filter(String sourceKind, Boolean active, String keyword) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(sourceKind)) {
                predicates.add(builder.equal(root.get("sourceKind"), sourceKind.trim().toUpperCase(Locale.ROOT)));
            }
            if (active != null) {
                predicates.add(builder.equal(root.get("active"), active));
            }
            if (StringUtils.hasText(keyword)) {
                String pattern = "%" + escapeLikePattern(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(builder.like(builder.lower(root.get("name")), pattern, LIKE_ESCAPE));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * 검색어에 들어온 LIKE 와일드카드를 리터럴로 취급한다. 예를 들어 "100%"는 접두사 검색이 아니라 그 문자열을 찾는다.
     */
    private static String escapeLikePattern(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
