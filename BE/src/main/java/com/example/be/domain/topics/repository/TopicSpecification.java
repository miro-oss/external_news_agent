package com.example.be.domain.topics.repository;

import com.example.be.domain.topics.entity.Topic;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TopicSpecification {

    private TopicSpecification() {
    }

    public static Specification<Topic> filter(Boolean active, String keyword) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (active != null) {
                predicates.add(builder.equal(root.get("active"), active));
            }
            if (StringUtils.hasText(keyword)) {
                String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.like(builder.lower(root.get("name")), pattern));
            }

            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
