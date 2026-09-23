package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.global.converter.LongListJsonConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Individual choices augment topic policies; exclusions also apply to captured run targets. */
@Repository
@RequiredArgsConstructor
public class ReportSubscriptionStore {
    private final JdbcTemplate jdbc;
    private static final LongListJsonConverter IDS = new LongListJsonConverter();

    @Transactional(readOnly = true)
    public List<Long> allowedTopics(Long recipientId, ReportScope scope, List<Long> topicIds) {
        List<Long> excluded = jdbc.queryForList(
                "SELECT topic_id FROM recipient_report_exclusions WHERE recipient_id=? AND report_scope=?",
                Long.class, recipientId, scope.name());
        return topicIds.stream().distinct().filter(id -> !excluded.contains(id)).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> includedRecipients(Long topicId, ReportScope scope) {
        return jdbc.queryForList(
                "SELECT recipient_id FROM recipient_report_inclusions WHERE topic_id=? AND report_scope=?",
                Long.class, topicId, scope.name());
    }

    @Transactional(readOnly = true)
    public boolean stillAllowed(Long outboxId, Long reportId, Long recipientId) {
        var rows = jdbc.query("""
                SELECT o.source_topic_ids,o.personal_topic_ids,o.channel_id,r.report_scope FROM report_notification_outbox o
                JOIN news_reports r ON r.id=o.report_id WHERE o.id=? AND o.report_id=? AND o.recipient_id=?
                """, (rs, n) -> new Consent(rs.getString("source_topic_ids"), rs.getString("personal_topic_ids"),
                        rs.getLong("channel_id"), ReportScope.valueOf(rs.getString("report_scope"))),
                outboxId, reportId, recipientId);
        if (rows.isEmpty()) return false;
        var consent = rows.getFirst();
        if (consent.topicIds() != null || consent.personalTopicIds() != null) {
            if (!allowedTopics(recipientId, consent.scope(), IDS.convertToEntityAttribute(consent.topicIds())).isEmpty()) return true;
            return allowedTopics(recipientId, consent.scope(), IDS.convertToEntityAttribute(consent.personalTopicIds())).stream()
                    .anyMatch(topicId -> personalPathStillActive(recipientId, topicId, consent.channelId(), consent.scope()));
        }
        // Old work cannot prove which included topic authorized delivery. Any applicable
        // exclusion cancels it, rather than inventing another topic's consent after enqueue.
        List<Long> related = jdbc.queryForList("""
                SELECT DISTINCT i.topic_id FROM news_collection_run_items i
                JOIN news_reports r ON r.id=?
                WHERE i.run_id=r.run_id OR i.run_id IN (
                    SELECT ids.run_id FROM JSON_TABLE(r.source_run_ids, '$[*]' COLUMNS(run_id NUMBER PATH '$')) ids
                )
                """, Long.class, reportId);
        return allowedTopics(recipientId, consent.scope(), related).size() == related.size();
    }

    private boolean personalPathStillActive(Long recipientId, Long topicId, Long channelId, ReportScope scope) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM recipient_report_inclusions i
                JOIN topic_delivery_policies p ON p.topic_id=i.topic_id
                WHERE i.recipient_id=? AND i.topic_id=? AND i.report_scope=? AND p.enabled_yn='Y'
                  AND EXISTS (SELECT 1 FROM JSON_TABLE(p.channel_ids, '$[*]' COLUMNS(id NUMBER PATH '$')) ids WHERE ids.id=?)
                  AND (EXISTS (SELECT 1 FROM JSON_TABLE(p.recipient_ids, '$[*]' COLUMNS(id NUMBER PATH '$')) ids WHERE ids.id=i.recipient_id)
                    OR EXISTS (SELECT 1 FROM notification_group_members m
                      JOIN notification_groups g ON g.id=m.group_id
                      WHERE m.recipient_id=i.recipient_id AND g.active_yn='Y'
                        AND EXISTS (SELECT 1 FROM JSON_TABLE(p.group_ids, '$[*]' COLUMNS(id NUMBER PATH '$')) ids WHERE ids.id=g.id)))
                """, Integer.class, recipientId, topicId, scope.name(), channelId);
        return count != null && count > 0;
    }

    private record Consent(String topicIds, String personalTopicIds, Long channelId, ReportScope scope) { }
}
