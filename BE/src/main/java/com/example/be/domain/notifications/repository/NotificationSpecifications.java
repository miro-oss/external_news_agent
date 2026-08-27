package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryLog;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.entity.GroupPerspective;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    public static Specification<NotificationRecipient> recipients(Boolean active,
                                                                   Long groupId,
                                                                   ChannelType channelType,
                                                                   String keyword) {
        return (root, query, cb) -> {
            query.distinct(true);
            var predicate = cb.conjunction();
            if (active != null) {
                predicate = cb.and(predicate, cb.equal(root.get("active"), active));
            }
            if (groupId != null) {
                predicate = cb.and(predicate,
                        cb.equal(root.join("groups", JoinType.INNER).get("id"), groupId));
            }
            if (channelType != null) {
                predicate = cb.and(predicate,
                        cb.equal(root.join("destinations", JoinType.INNER)
                                .join("channel", JoinType.INNER).get("channelType"), channelType));
            }
            if (StringUtils.hasText(keyword)) {
                predicate = cb.and(predicate, cb.like(cb.lower(root.get("name")),
                        "%" + keyword.trim().toLowerCase() + "%"));
            }
            return predicate;
        };
    }

    public static Specification<NotificationGroup> groups(Boolean active, GroupPerspective perspective) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (active != null) {
                predicate = cb.and(predicate, cb.equal(root.get("active"), active));
            }
            if (perspective != null) {
                predicate = cb.and(predicate, cb.equal(root.get("perspective"), perspective));
            }
            return predicate;
        };
    }

    public static Specification<DeliveryLog> deliveryLogs(Long reportId,
                                                           Long runId,
                                                           String batchId,
                                                           ChannelType channelType,
                                                           DeliveryStatus status,
                                                           Long recipientId,
                                                           LocalDateTime from,
                                                           LocalDateTime to) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (reportId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("report").get("id"), reportId));
            }
            if (runId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("report").get("run").get("id"), runId));
            }
            if (StringUtils.hasText(batchId)) {
                predicate = cb.and(predicate, cb.equal(root.get("batch").get("id"), batchId.trim()));
            }
            if (channelType != null) {
                predicate = cb.and(predicate, cb.equal(root.get("channelType"), channelType));
            }
            if (status != null) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            if (recipientId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("recipient").get("id"), recipientId));
            }
            if (from != null) {
                predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("sentAt"), from));
            }
            if (to != null) {
                predicate = cb.and(predicate, cb.lessThanOrEqualTo(root.get("sentAt"), to));
            }
            return predicate;
        };
    }
}
