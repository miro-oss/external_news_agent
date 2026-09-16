package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RunDeliverySettingsService {
    public static final String CLOSED_MESSAGE = "보고서가 완성되었거나 수집이 종료되어 알림 설정을 변경할 수 없습니다.";
    private final CollectionRunRepository runs;
    private final NewsReportRepository reports;
    private final RunDeliverySnapshotStore snapshots;
    private final CollectionRunDeliveryService delivery;
    private final ReportNotificationAutomationService automation;

    public record TopicPolicy(Long topicId, String topicName, boolean enabled, boolean run, boolean daily,
                              List<Long> groupIds, List<Long> recipientIds, List<Long> channelIds) { }
    public record Settings(Long runId, boolean editable, Long reportId, boolean reportReady, String source,
                           boolean enabled, boolean run, boolean daily, List<Long> groupIds,
                           List<Long> recipientIds, List<Long> channelIds, List<TopicPolicy> topicPolicies) { }

    // The short run lock also prevents a GET seeing old settings with newly replaced targets.
    @Transactional
    public Settings get(Long runId) {
        CollectionRun run = lockedRun(runId);
        return settings(run, reports.findByRunId(runId).orElse(null), snapshots.find(runId).orElse(null));
    }

    @Transactional
    public Settings save(Long runId, NotificationReqDTO.RunDeliverySettings request) {
        CollectionRun run = lockedRun(runId);
        NewsReport report = reports.findByRunId(runId).orElse(null);
        if (!editable(run, report)) throw new GeneralException(GeneralErrorCode.CONFLICT, CLOSED_MESSAGE);
        if (request == null) throw invalid("자동 전달 설정이 필요합니다.");
        if (request.getEnabled() == null) throw invalid("자동 전달 사용 여부를 선택해 주세요.");
        if (request.getRun() == null || request.getDaily() == null) throw invalid("전달할 보고서 종류를 선택해 주세요.");
        var policy = new ReportNotificationAutomationService.Policy(request.getEnabled(), request.getRun(), request.getDaily(),
                request.getGroupIds(), request.getRecipientIds(), request.getChannelIds());
        var previous = snapshots.find(runId).orElse(null);
        var preservedTargets = previous != null && sameSelection(previous.selection(), policy) ? previous.targets() : null;
        var prepared = delivery.prepareSelection("ONCE", policy, preservedTargets);
        snapshots.replace(runId, prepared.snapshot());
        return settings(run, report, prepared.snapshot());
    }

    private Settings settings(CollectionRun run, NewsReport report, RunDeliverySnapshotStore.Snapshot snapshot) {
        if (snapshot != null) {
            var selection = snapshot.selection();
            return new Settings(run.getId(), editable(run, report), report == null ? null : report.getId(), ready(report), "RUN",
                    snapshot.enabled(), snapshot.run(), snapshot.daily(), selection.groupIds(), selection.recipientIds(),
                    selection.channelIds(), List.of());
        }
        var policies = new LinkedHashMap<Long, TopicPolicy>();
        for (var item : run.getItems()) {
            var topic = item.getTopic();
            if (policies.containsKey(topic.getId())) continue;
            var policy = automation.policy(topic.getId());
            policies.put(topic.getId(), new TopicPolicy(topic.getId(), topic.getName(), policy.enabled(), policy.run(), policy.daily(),
                    policy.groupIds(), policy.recipientIds(), policy.channelIds()));
        }
        // A new override draft, not a lossy union of independently scoped topic policies.
        return new Settings(run.getId(), editable(run, report), report == null ? null : report.getId(), ready(report), "TOPIC",
                false, true, false, List.of(), List.of(), List.of(), List.copyOf(policies.values()));
    }

    private CollectionRun lockedRun(Long runId) {
        return runs.findByIdForUpdate(runId).orElseThrow(() -> new RunException(RunErrorCode.RUN_NOT_FOUND));
    }
    private static boolean ready(NewsReport report) { return report != null && report.getReportStatus() != ReportStatus.PENDING; }
    private static boolean editable(CollectionRun run, NewsReport report) { return run.getStatus().isInProgress() && !ready(report); }
    private static boolean sameSelection(RunDeliverySnapshotStore.Selection selection, ReportNotificationAutomationService.Policy policy) {
        return new HashSet<>(selection.groupIds()).equals(new HashSet<>(policy.groupIds()))
                && new HashSet<>(selection.recipientIds()).equals(new HashSet<>(policy.recipientIds()))
                && new HashSet<>(selection.channelIds()).equals(new HashSet<>(policy.channelIds()));
    }
    private static GeneralException invalid(String message) { return new GeneralException(GeneralErrorCode.BAD_REQUEST, message); }
}
