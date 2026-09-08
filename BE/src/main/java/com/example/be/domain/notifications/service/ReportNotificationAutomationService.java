package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.*;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.converter.LongListJsonConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Saves one delivery per report/recipient/channel in the report-completion transaction. */
@Service
@RequiredArgsConstructor
public class ReportNotificationAutomationService {
    private final JdbcTemplate jdbc;
    private final TopicRepository topics;
    private final NotificationManagementService management;
    private final NotificationDeliveryPlanService plans;
    private final NotificationRenderer renderer;
    private static final LongListJsonConverter IDS = new LongListJsonConverter();

    public record Policy(boolean enabled, boolean run, boolean daily, List<Long> groupIds,
                         List<Long> recipientIds, List<Long> channelIds) {
        public Policy {
            groupIds = ids(groupIds); recipientIds = ids(recipientIds); channelIds = ids(channelIds);
        }
        static Policy empty() { return new Policy(false, true, false, List.of(), List.of(), List.of()); }
    }

    @Transactional(readOnly = true)
    public Policy policy(Long topicId) {
        requireTopic(topicId);
        return jdbc.query("SELECT * FROM topic_delivery_policies WHERE topic_id=?", (rs, n) -> new Policy(
                "Y".equals(rs.getString("enabled_yn")), "Y".equals(rs.getString("run_yn")),
                "Y".equals(rs.getString("daily_yn")), IDS.convertToEntityAttribute(rs.getString("group_ids")),
                IDS.convertToEntityAttribute(rs.getString("recipient_ids")),
                IDS.convertToEntityAttribute(rs.getString("channel_ids"))), topicId).stream().findFirst().orElse(Policy.empty());
    }

    @Transactional
    public Policy savePolicy(Long topicId, Policy policy) {
        requireTopic(topicId);
        if (policy == null) throw invalid("자동 전달 설정이 필요합니다.");
        if (policy.enabled() && (!policy.run() && !policy.daily())) throw invalid("전달할 보고서 종류를 선택해 주세요.");
        if (policy.enabled() && policy.groupIds().isEmpty() && policy.recipientIds().isEmpty()) throw invalid("수신 그룹이나 수신자를 선택해 주세요.");
        if (policy.enabled() && policy.channelIds().isEmpty()) throw invalid("전달 채널을 선택해 주세요.");
        if (policy.enabled()) {
            policy.groupIds().forEach(id -> management.findGroup(id, true));
            policy.recipientIds().forEach(id -> {
                if (!management.findRecipient(id).isActive()) throw invalid("활성 수신자를 선택해 주세요.");
            });
            policy.channelIds().forEach(id -> management.findChannel(id, true));
        }
        // Serialize concurrent edits against an existing topic row.
        jdbc.queryForObject("SELECT id FROM news_topics WHERE id=? FOR UPDATE", Long.class, topicId);
        jdbc.update("DELETE FROM topic_delivery_policies WHERE topic_id=?", topicId);
        jdbc.update("INSERT INTO topic_delivery_policies(topic_id,enabled_yn,run_yn,daily_yn,group_ids,recipient_ids,channel_ids) VALUES(?,?,?,?,?,?,?)",
                topicId, yn(policy.enabled()), yn(policy.run()), yn(policy.daily()), IDS.convertToDatabaseColumn(policy.groupIds()),
                IDS.convertToDatabaseColumn(policy.recipientIds()), IDS.convertToDatabaseColumn(policy.channelIds()));
        return policy;
    }

    @Transactional
    public void enqueueCompletedReport(NewsReport report) {
        List<Long> runIds = report.getReportScope() == ReportScope.DAILY ? report.getSourceRunIds()
                : report.getRunId() == null ? List.of() : List.of(report.getRunId());
        if (runIds.isEmpty()) return;
        Set<Long> topicIds = new LinkedHashSet<>();
        runIds.forEach(id -> topicIds.addAll(jdbc.queryForList(
                "SELECT DISTINCT topic_id FROM news_collection_run_items WHERE run_id=?", Long.class, id)));
        Map<String, NotificationDeliveryPlanService.PreparedTarget> targets = new LinkedHashMap<>();
        for (Long topicId : topicIds) {
            Policy policy = policy(topicId);
            if (!policy.enabled() || (report.getReportScope() == ReportScope.DAILY ? !policy.daily() : !policy.run())) continue;
            // Stale/deactivated targets are omitted, not allowed to fail report persistence.
            List<NotificationGroup> groups = policy.groupIds().stream().map(id -> optionalGroup(id)).filter(Objects::nonNull).toList();
            List<NotificationChannel> channels = policy.channelIds().stream().map(id -> optionalChannel(id)).filter(Objects::nonNull).toList();
            List<Long> recipientIds = policy.recipientIds().stream().filter(this::activeRecipient).toList();
            plans.resolveTargets(groups, channels, recipientIds).forEach(target ->
                    targets.putIfAbsent(target.recipientId() + ":" + target.channel().getId(), target));
        }
        for (var target : targets.values()) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM report_notification_outbox WHERE report_id=? AND recipient_id=? AND channel_id=?",
                    Integer.class, report.getId(), target.recipientId(), target.channel().getId());
            if (exists != null && exists > 0) continue;
            RenderedNotification message = renderer.render(report, target.channel());
            String batchId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
            jdbc.update("INSERT INTO notification_delivery_batches(id,report_id,idempotency_key,requested_at) VALUES(?,?,?,?)",
                    batchId, report.getId(), "auto:" + report.getId() + ":" + target.recipientId() + ":" + target.channel().getId(), now);
            jdbc.update("INSERT INTO report_notification_outbox(report_id,recipient_id,channel_id,batch_id,recipient_name,address,subject,body,available_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    report.getId(), target.recipientId(), target.channel().getId(), batchId, target.recipientName(), target.address(),
                    message.subject(), String.join("\n", message.chunks()), now);
        }
    }

    private NotificationGroup optionalGroup(Long id) { try { return management.findGroup(id, true); } catch (com.example.be.domain.notifications.exception.NotificationException ex) { return null; } }
    private NotificationChannel optionalChannel(Long id) { try { return management.findChannel(id, true); } catch (com.example.be.domain.notifications.exception.NotificationException ex) { return null; } }
    private boolean activeRecipient(Long id) { try { return management.findRecipient(id).isActive(); } catch (com.example.be.domain.notifications.exception.NotificationException ex) { return false; } }
    private void requireTopic(Long id) { if (!topics.existsById(id)) throw new GeneralException(GeneralErrorCode.NOT_FOUND, "수집 주제를 찾을 수 없습니다."); }
    static List<Long> ids(List<Long> values) {
        if (values == null) return List.of();
        if (values.size() > 100 || values.stream().anyMatch(id -> id == null || id <= 0)) throw invalid("선택 값이 올바르지 않습니다.");
        return values.stream().distinct().toList();
    }
    private static GeneralException invalid(String message) { return new GeneralException(GeneralErrorCode.BAD_REQUEST, message); }
    private static String yn(boolean value) { return value ? "Y" : "N"; }
}
