package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.converter.LongListJsonConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecipientReportSubscriptionService {
    private final JdbcTemplate jdbc;
    private final NotificationManagementService management;
    private final TopicRepository topics;
    private static final LongListJsonConverter IDS = new LongListJsonConverter();
    private static final List<String> SCOPES = List.of("RUN", "DAILY", "WEEKLY");
    private static final String INVALID_SCOPES = "제외할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요.";
    private static final String INVALID_INCLUSIONS = "추가할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요.";

    public record Subscriptions(Long recipientId, List<TopicSubscription> topics) { }

    public record TopicSubscription(Long topicId, String topicName,
                                    @Schema(description = "저장된 주제 정책의 활성 여부") boolean enabled,
                                    @Schema(description = "주제에 설정한 보고서 종류. RUN, DAILY, WEEKLY 순") List<String> configuredScopes,
                                    @Schema(description = "이 수신자만 제외한 보고서 종류") List<String> excludedScopes,
                                    @Schema(description = "이 수신자가 추가로 선택한 보고서 종류") List<String> includedScopes,
                                    @Schema(description = "현재 연결된 수신 경로의 채널 종류") List<String> channelTypes,
                                    boolean direct, List<String> groupNames) { }

    public record Exclusions(@Schema(description = "필수. RUN, DAILY, WEEKLY만 허용하며 최대 3개. []는 개인 제외 해제", requiredMode = Schema.RequiredMode.REQUIRED)
                             List<String> excludedScopes,
                             @Schema(description = "RUN, DAILY, WEEKLY만 허용하며 최대 3개. []는 개인 추가 초기화. 생략/null은 기존 추가에서 새 제외 종류만 제거")
                             List<String> includedScopes) {
        public Exclusions(List<String> excludedScopes) { this(excludedScopes, null); }
    }

    private record SavedPolicy(Long topicId, String topicName, boolean enabled, List<String> scopes,
                               List<Long> groupIds, List<Long> recipientIds, List<Long> channelIds) { }

    @Transactional(readOnly = true)
    public Subscriptions get(Long recipientId) {
        NotificationRecipient recipient = management.findRecipient(recipientId);
        var exclusions = exclusions(recipientId);
        var inclusions = inclusions(recipientId);
        var rows = policies().stream().map(policy -> row(recipient, policy, exclusions.getOrDefault(policy.topicId(), List.of()),
                        inclusions.getOrDefault(policy.topicId(), List.of())))
                .filter(row -> row.direct() || !row.groupNames().isEmpty() || !row.excludedScopes().isEmpty() || !row.includedScopes().isEmpty())
                .toList();
        return new Subscriptions(recipientId, rows);
    }

    @Transactional
    public TopicSubscription save(Long recipientId, Long topicId, Exclusions request) {
        NotificationRecipient recipient = management.findRecipient(recipientId);
        var topic = topics.findById(topicId).orElseThrow(() ->
                new GeneralException(GeneralErrorCode.NOT_FOUND, "수집 주제를 찾을 수 없습니다."));
        if (request == null || request.excludedScopes() == null || invalidScopes(request.excludedScopes()))
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, INVALID_SCOPES);
        if (request.includedScopes() != null && invalidScopes(request.includedScopes()))
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, INVALID_INCLUSIONS);
        List<String> excluded = SCOPES.stream().filter(request.excludedScopes()::contains).toList();
        if (request.includedScopes() != null && request.includedScopes().stream().anyMatch(excluded::contains))
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "같은 보고서 종류를 추가와 제외에 동시에 선택할 수 없습니다.");
        // Concurrent full replacements for one recipient must not interleave DELETE/INSERT.
        jdbc.queryForObject("SELECT id FROM notification_recipients WHERE id=? FOR UPDATE", Long.class, recipientId);
        List<String> requestedInclusions = request.includedScopes() == null
                ? inclusions(recipientId).getOrDefault(topicId, List.of()) : request.includedScopes();
        List<String> included = SCOPES.stream().filter(requestedInclusions::contains).filter(scope -> !excluded.contains(scope)).toList();
        jdbc.update("DELETE FROM recipient_report_exclusions WHERE recipient_id=? AND topic_id=?", recipientId, topicId);
        for (String scope : excluded) jdbc.update(
                "INSERT INTO recipient_report_exclusions(recipient_id,topic_id,report_scope) VALUES(?,?,?)", recipientId, topicId, scope);
        jdbc.update("DELETE FROM recipient_report_inclusions WHERE recipient_id=? AND topic_id=?", recipientId, topicId);
        for (String scope : included) jdbc.update(
                "INSERT INTO recipient_report_inclusions(recipient_id,topic_id,report_scope) VALUES(?,?,?)", recipientId, topicId, scope);
        var policy = policies().stream().filter(saved -> saved.topicId().equals(topicId)).findFirst()
                .orElse(new SavedPolicy(topicId, topic.getName(), false, List.of(), List.of(), List.of(), List.of()));
        return row(recipient, policy, excluded, included);
    }

    private static boolean invalidScopes(List<String> scopes) {
        return scopes.size() > 3 || scopes.stream().anyMatch(scope -> scope == null || !SCOPES.contains(scope));
    }

    private Map<Long, List<String>> exclusions(Long recipientId) {
        return jdbc.query("SELECT topic_id,report_scope FROM recipient_report_exclusions WHERE recipient_id=?",
                        (rs, n) -> Map.entry(rs.getLong("topic_id"), rs.getString("report_scope")), recipientId).stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private Map<Long, List<String>> inclusions(Long recipientId) {
        return jdbc.query("SELECT topic_id,report_scope FROM recipient_report_inclusions WHERE recipient_id=?",
                        (rs, n) -> Map.entry(rs.getLong("topic_id"), rs.getString("report_scope")), recipientId).stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private List<SavedPolicy> policies() {
        return jdbc.query("""
                SELECT t.id,t.name,p.enabled_yn,p.run_yn,p.daily_yn,p.weekly_yn,p.group_ids,p.recipient_ids,p.channel_ids
                FROM news_topics t LEFT JOIN topic_delivery_policies p ON p.topic_id=t.id ORDER BY t.name,t.id
                """, (rs, n) -> {
            var scopes = new ArrayList<String>();
            if ("Y".equals(rs.getString("run_yn"))) scopes.add("RUN");
            if ("Y".equals(rs.getString("daily_yn"))) scopes.add("DAILY");
            if ("Y".equals(rs.getString("weekly_yn"))) scopes.add("WEEKLY");
            return new SavedPolicy(rs.getLong("id"), rs.getString("name"), "Y".equals(rs.getString("enabled_yn")),
                    List.copyOf(scopes), IDS.convertToEntityAttribute(rs.getString("group_ids")),
                    IDS.convertToEntityAttribute(rs.getString("recipient_ids")), IDS.convertToEntityAttribute(rs.getString("channel_ids")));
        });
    }

    private TopicSubscription row(NotificationRecipient recipient, SavedPolicy policy, List<String> excluded, List<String> included) {
        boolean direct = policy.recipientIds().contains(recipient.getId());
        List<NotificationGroup> matchingGroups = recipient.getGroups().stream()
                .filter(group -> policy.groupIds().contains(group.getId())).toList();
        boolean targeted = direct || !matchingGroups.isEmpty();
        boolean activeTarget = direct || matchingGroups.stream().anyMatch(NotificationGroup::isActive);
        List<String> channelTypes = !recipient.isActive() || !activeTarget ? List.of() : recipient.getDestinations().stream()
                .filter(RecipientDestination::isUse)
                .filter(destination -> StringUtils.hasText(destination.getAddress()))
                .filter(destination -> destination.getChannel().isActive() && policy.channelIds().contains(destination.getChannel().getId()))
                .filter(destination -> destination.getChannel().getChannelType() == ChannelType.EMAIL || destination.isOnboarded())
                .map(destination -> destination.getChannel().getChannelType().name()).distinct().sorted().toList();
        return new TopicSubscription(policy.topicId(), policy.topicName(), targeted && policy.enabled(),
                targeted ? policy.scopes() : List.of(), SCOPES.stream().filter(excluded::contains).toList(),
                SCOPES.stream().filter(included::contains).toList(), channelTypes,
                direct, matchingGroups.stream().map(NotificationGroup::getName).distinct().sorted().toList());
    }
}
