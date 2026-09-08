package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CollectionRunDeliveryService {
    private final NotificationManagementService management;
    private final NotificationDeliveryPlanService plans;
    private final ReportNotificationAutomationService automation;
    private final RunDeliverySnapshotStore snapshots;

    public record Prepared(ReportNotificationAutomationService.Policy policy, RunDeliverySnapshotStore.Snapshot snapshot) { }

    @Transactional(readOnly = true)
    public Prepared prepare(CollectionRunReqDTO.Delivery request) {
        if (request == null) return null;
        if (request.getEnabled() == null) throw invalid("자동 전달 사용 여부를 선택해 주세요.");
        if (!"ONCE".equals(request.getMode()) && !"TOPIC".equals(request.getMode()))
            throw invalid("전달 적용 범위는 ONCE 또는 TOPIC이어야 합니다.");
        var policy = new ReportNotificationAutomationService.Policy(request.getEnabled(),
                !Boolean.FALSE.equals(request.getRun()), Boolean.TRUE.equals(request.getDaily()),
                request.getGroupIds(), request.getRecipientIds(), request.getChannelIds());
        if (!policy.enabled()) return prepared(request.getMode(), policy, List.of());
        if (!policy.run() && !policy.daily()) throw invalid("전달할 보고서 종류를 선택해 주세요.");
        if (policy.groupIds().isEmpty() && policy.recipientIds().isEmpty()) throw invalid("수신 그룹이나 수신자를 선택해 주세요.");
        if (policy.channelIds().isEmpty()) throw invalid("전달 채널을 선택해 주세요.");
        var groups = policy.groupIds().stream().map(id -> management.findGroup(id, true)).toList();
        var channels = policy.channelIds().stream().map(id -> management.findChannel(id, true)).toList();
        policy.recipientIds().forEach(id -> {
            if (!management.findRecipient(id).isActive()) throw invalid("활성 수신자를 선택해 주세요.");
        });
        var targets = new LinkedHashMap<String, RunDeliverySnapshotStore.Target>();
        for (var target : plans.resolveTargets(groups, channels, policy.recipientIds())) {
            if (target.channelType() == ChannelType.TELEGRAM && !target.onboarded()) continue;
            targets.putIfAbsent(target.recipientId() + ":" + target.channel().getId(),
                    new RunDeliverySnapshotStore.Target(target.recipientId(), target.recipientName(), target.channel().getId(), target.address()));
        }
        if (targets.isEmpty()) throw invalid("선택한 대상에 연결된 수신 채널이 없습니다.");
        return prepared(request.getMode(), policy, List.copyOf(targets.values()));
    }

    @Transactional
    public void save(Long runId, List<Long> topicIds, Prepared prepared) {
        if (prepared == null) return;
        if ("TOPIC".equals(prepared.snapshot().mode())) {
            topicIds.stream().distinct().sorted().forEach(id -> automation.savePolicy(id, prepared.policy()));
        }
        snapshots.save(runId, prepared.snapshot());
    }

    private Prepared prepared(String mode, ReportNotificationAutomationService.Policy policy, List<RunDeliverySnapshotStore.Target> targets) {
        return new Prepared(policy, new RunDeliverySnapshotStore.Snapshot(mode, policy.enabled(), policy.run(), policy.daily(), targets));
    }

    private static GeneralException invalid(String message) { return new GeneralException(GeneralErrorCode.BAD_REQUEST, message); }
}
