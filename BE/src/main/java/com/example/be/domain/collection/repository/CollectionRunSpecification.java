package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CollectionRunSpecification {

    private CollectionRunSpecification() {
    }

    public static Specification<CollectionRun> filter(RunStatus status,
                                                      TriggerType triggerType,
                                                      Long topicId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            if (triggerType != null) {
                predicates.add(criteriaBuilder.equal(root.get("triggerType"), triggerType));
            }
            if (topicId != null) {
                Join<CollectionRun, CollectionRunItem> items = root.join("items");
                predicates.add(criteriaBuilder.equal(items.get("topic").get("id"), topicId));
                query.distinct(true);
            }
            if (from != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("startedAt"), from));
            }
            if (to != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("startedAt"), to));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
