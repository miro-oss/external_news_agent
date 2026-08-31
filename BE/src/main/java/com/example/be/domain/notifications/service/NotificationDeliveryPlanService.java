package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.exception.code.NotificationErrorCode;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.repository.NotificationGroupRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 발송 전에 필요한 LAZY 연관과 렌더링 결과를 짧은 읽기 트랜잭션 안에서 스냅샷으로 만든다. */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryPlanService {

    private final NewsReportRepository reportRepository;
    private final NotificationChannelRepository channelRepository;
    private final NotificationGroupRepository groupRepository;
    private final NotificationManagementService managementService;
    private final NotificationRenderer renderer;

    @Transactional(readOnly = true)
    public void requireReport(Long reportId) {
        findReport(reportId);
    }

    @Transactional(readOnly = true)
    public PreparedDelivery prepare(Long reportId, NotificationReqDTO.Send request) {
        NewsReport report = findReport(reportId);
        List<NotificationChannel> channels = resolveChannels(request.getChannelIds());
        List<NotificationGroup> groups = request.getGroupIds().stream().filter(Objects::nonNull).distinct()
                .map(id -> managementService.findGroup(id, true)).toList();
        List<PreparedTarget> targets = resolveTargets(groups, channels);
        if (targets.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.DELIVERY_NO_TARGET,
                    Map.of("groupIds", request.getGroupIds()));
        }

        Map<Long, RenderedNotification> renderedByChannel = new LinkedHashMap<>();
        channels.forEach(channel -> renderedByChannel.put(channel.getId(), renderer.render(report, channel)));
        return new PreparedDelivery(reportId, targets, renderedByChannel);
    }

    @Transactional(readOnly = true)
    public PreparedWatchDelivery prepareWatchAlert(Long notifyGroupId,
                                                    String issueTitle,
                                                    String message) {
        List<NotificationChannel> channels = resolveChannels(List.of());
        // 자동 BREAKING watch는 그룹을 미리 선택하지 않으므로 null이면 활성 그룹 전체로 보낸다.
        List<NotificationGroup> groups = notifyGroupId == null
                ? groupRepository.findAllByActiveOrderByIdAsc(true)
                : groupRepository.findByIdAndActive(notifyGroupId, true).stream().toList();
        List<PreparedTarget> targets = resolveTargets(groups, channels);
        Map<Long, RenderedNotification> renderedByChannel = new LinkedHashMap<>();
        channels.forEach(channel -> renderedByChannel.put(
                channel.getId(), renderer.renderBreakingAlert(issueTitle, message, channel)));
        return new PreparedWatchDelivery(targets, renderedByChannel);
    }

    private List<PreparedTarget> resolveTargets(List<NotificationGroup> groups,
                                                List<NotificationChannel> channels) {
        Map<String, PreparedTarget> targets = new LinkedHashMap<>();
        for (NotificationGroup group : groups) {
            for (NotificationRecipient recipient : group.getMembers()) {
                if (!recipient.isActive()) {
                    continue;
                }
                Map<Long, RecipientDestination> destinations = recipient.getDestinations().stream()
                        .filter(RecipientDestination::isUse)
                        .filter(destination -> StringUtils.hasText(destination.getAddress()))
                        .collect(java.util.stream.Collectors.toMap(
                                destination -> destination.getChannel().getId(), value -> value));
                for (NotificationChannel channel : channels) {
                    RecipientDestination destination = destinations.get(channel.getId());
                    if (destination != null) {
                        targets.putIfAbsent(recipient.getId() + ":" + channel.getId(), new PreparedTarget(
                                recipient.getId(), recipient.getName(), channel, destination.getId(),
                                destination.getAddress(), destination.isOnboarded()));
                    }
                }
            }
        }
        return List.copyOf(targets.values());
    }

    private List<NotificationChannel> resolveChannels(List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return channelRepository.findAllByActiveOrderByIdAsc(true);
        }
        return channelIds.stream().filter(Objects::nonNull).distinct()
                .map(id -> managementService.findChannel(id, true)).toList();
    }

    private NewsReport findReport(Long reportId) {
        return reportRepository.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
    }

    public record PreparedDelivery(Long reportId,
                                   List<PreparedTarget> targets,
                                   Map<Long, RenderedNotification> renderedByChannel) {
    }

    public record PreparedWatchDelivery(
            List<PreparedTarget> targets,
            Map<Long, RenderedNotification> renderedByChannel) {
    }

    public record PreparedTarget(Long recipientId,
                                 String recipientName,
                                 NotificationChannel channel,
                                 Long destinationId,
                                 String address,
                                 boolean onboarded) {
        public ChannelType channelType() {
            return channel.getChannelType();
        }
    }
}
