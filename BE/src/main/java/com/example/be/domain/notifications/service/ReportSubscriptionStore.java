package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.global.converter.LongListJsonConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Individual exclusions apply to every automatic path, including captured run targets. */
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
    public boolean stillAllowed(Long outboxId, Long reportId, Long recipientId) {
        var rows = jdbc.query("""
                SELECT o.source_topic_ids,r.report_scope FROM report_notification_outbox o
                JOIN news_reports r ON r.id=o.report_id WHERE o.id=? AND o.report_id=? AND o.recipient_id=?
                """, (rs, n) -> new Consent(rs.getString("source_topic_ids"), ReportScope.valueOf(rs.getString("report_scope"))),
                outboxId, reportId, recipientId);
        if (rows.isEmpty()) return false;
        var consent = rows.getFirst();
        if (consent.topicIds() != null) {
            return !allowedTopics(recipientId, consent.scope(), IDS.convertToEntityAttribute(consent.topicIds())).isEmpty();
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

    private record Consent(String topicIds, ReportScope scope) { }
}
