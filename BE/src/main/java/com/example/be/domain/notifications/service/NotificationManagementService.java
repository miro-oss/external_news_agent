package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.dto.res.NotificationResDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.GroupPerspective;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.exception.code.NotificationErrorCode;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.repository.NotificationGroupRepository;
import com.example.be.domain.notifications.repository.NotificationRecipientRepository;
import com.example.be.domain.notifications.repository.NotificationSpecifications;
import com.example.be.domain.notifications.repository.RecipientDestinationRepository;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.BaseErrorCode;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationManagementService {

    private final NotificationChannelRepository channelRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final RecipientDestinationRepository destinationRepository;
    private final NotificationGroupRepository groupRepository;
    private final NotificationSenderRegistry senderRegistry;

    public List<NotificationResDTO.Channel> getChannels(Boolean active) {
        List<NotificationChannel> channels = active == null
                ? channelRepository.findAllByOrderByIdAsc()
                : channelRepository.findAllByActiveOrderByIdAsc(active);
        return channels.stream().map(channel -> toChannel(channel, true)).toList();
    }

    @Transactional
    public NotificationResDTO.Channel updateChannel(Long channelId, NotificationReqDTO.ChannelUpdate request) {
        NotificationChannel channel = findChannel(channelId, false);
        String name = request.getName() == null ? channel.getName()
                : required(request.getName(), "채널명은 필수입니다.", NotificationErrorCode.CHANNEL_INVALID);
        Map<String, Object> config = request.getConfig() == null ? channel.getConfig() : request.getConfig();
        int maxLength = request.getMaxLength() == null ? channel.getMaxLength() : request.getMaxLength();
        boolean active = request.getActive() == null ? channel.isActive() : request.getActive();
        validateChannel(channel.getChannelType(), config, maxLength);
        channel.update(name, config, maxLength, active);
        return toChannel(channel, false);
    }

    public PageResponse<NotificationResDTO.Recipient> getRecipients(Boolean active,
                                                                    Long groupId,
                                                                    String channelType,
                                                                    String keyword,
                                                                    int page,
                                                                    int size) {
        validatePage(page, size);
        if (groupId != null && !groupRepository.existsById(groupId)) {
            throw new NotificationException(NotificationErrorCode.GROUP_NOT_FOUND);
        }
        ChannelType parsedType = parseChannelType(channelType);
        var result = recipientRepository.findAll(
                NotificationSpecifications.recipients(active, groupId, parsedType, keyword),
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
        return PageResponse.of(result.getContent().stream().map(this::toRecipient).toList(),
                page, size, result.getTotalElements());
    }

    @Transactional
    public NotificationResDTO.Recipient createRecipient(NotificationReqDTO.RecipientCreate request) {
        String name = required(request.getName(), "수신자명은 필수입니다.", NotificationErrorCode.RECIPIENT_INVALID);
        validateLength(name, 100, "수신자명은 100자 이하여야 합니다.", NotificationErrorCode.RECIPIENT_INVALID);
        NotificationRecipient recipient = NotificationRecipient.builder()
                .name(name)
                .phone(trimToNull(request.getPhone()))
                .email(trimToNull(request.getEmail()))
                .memo(trimToNull(request.getMemo()))
                .active(request.getActive() == null || request.getActive())
                .destinations(new ArrayList<>())
                .groups(new ArrayList<>())
                .build();
        recipient.replaceDestinations(buildDestinations(null, request.getDestinations(), List.of()));
        recipientRepository.save(recipient);
        return toRecipient(recipient, false);
    }

    @Transactional
    public NotificationResDTO.RecipientBasic updateRecipient(Long recipientId,
                                                              NotificationReqDTO.RecipientUpdate request) {
        NotificationRecipient recipient = findRecipient(recipientId);
        String name = request.getName() == null ? recipient.getName()
                : required(request.getName(), "수신자명은 필수입니다.", NotificationErrorCode.RECIPIENT_INVALID);
        validateLength(name, 100, "수신자명은 100자 이하여야 합니다.", NotificationErrorCode.RECIPIENT_INVALID);
        recipient.update(name,
                request.getPhone() == null ? recipient.getPhone() : trimToNull(request.getPhone()),
                request.getEmail() == null ? recipient.getEmail() : trimToNull(request.getEmail()),
                request.getMemo() == null ? recipient.getMemo() : trimToNull(request.getMemo()),
                request.getActive() == null ? recipient.isActive() : request.getActive());
        return toRecipientBasic(recipient);
    }

    @Transactional
    public NotificationResDTO.RecipientDeleted deleteRecipient(Long recipientId) {
        NotificationRecipient recipient = findRecipient(recipientId);
        List<NotificationGroup> groups = new ArrayList<>(recipient.getGroups());
        groups.forEach(group -> group.getMembers().removeIf(member -> member.getId().equals(recipientId)));
        LocalDateTime deletedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        recipient.softDelete(deletedAt);
        return NotificationResDTO.RecipientDeleted.builder()
                .id(recipient.getId())
                .active(false)
                .deletedAt(toOffset(deletedAt))
                .removedGroupCount(groups.size())
                .build();
    }

    @Transactional
    public NotificationResDTO.Destinations replaceDestinations(Long recipientId,
                                                                NotificationReqDTO.DestinationsUpdate request) {
        NotificationRecipient recipient = findRecipient(recipientId);
        List<RecipientDestination> replacements = buildDestinations(
                recipientId, request.getDestinations(), recipient.getDestinations());
        // 같은 (recipient, channel)을 새 주소로 교체할 때 INSERT가 기존 행 DELETE보다 먼저 실행되면
        // Oracle의 복합 UNIQUE 제약에 걸린다. 기존 고아 행을 먼저 확정해서 교체 순서를 보장한다.
        recipient.replaceDestinations(List.of());
        recipientRepository.flush();
        recipient.replaceDestinations(replacements);
        recipientRepository.flush();
        return NotificationResDTO.Destinations.builder()
                .recipientId(recipientId)
                .destinations(recipient.getDestinations().stream().map(this::toDestination).toList())
                .build();
    }

    public PageResponse<NotificationResDTO.Group> getGroups(Boolean active,
                                                             String perspective,
                                                             int page,
                                                             int size) {
        validatePage(page, size);
        GroupPerspective parsed = parsePerspective(perspective);
        var result = groupRepository.findAll(NotificationSpecifications.groups(active, parsed),
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
        return PageResponse.of(result.getContent().stream().map(group -> toGroup(group, false)).toList(),
                page, size, result.getTotalElements());
    }

    @Transactional
    public NotificationResDTO.Group createGroup(NotificationReqDTO.GroupCreate request) {
        String name = required(request.getName(), "그룹명은 필수입니다.", GeneralErrorCode.BAD_REQUEST);
        validateLength(name, 100, "그룹명은 100자 이하여야 합니다.", GeneralErrorCode.BAD_REQUEST);
        if (groupRepository.existsByName(name)) {
            throw new NotificationException(NotificationErrorCode.GROUP_DUPLICATE);
        }
        List<NotificationRecipient> members = findRecipients(request.getRecipientIds());
        NotificationGroup group = NotificationGroup.builder()
                .name(name)
                .perspective(parsePerspective(request.getPerspective()))
                .active(request.getActive() == null || request.getActive())
                .createdAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .members(new ArrayList<>())
                .build();
        group.replaceMembers(members);
        groupRepository.save(group);
        return toGroup(group, true);
    }

    @Transactional
    public NotificationResDTO.Group updateGroup(Long groupId, NotificationReqDTO.GroupUpdate request) {
        NotificationGroup group = findGroup(groupId, false);
        String name = request.getName() == null ? group.getName()
                : required(request.getName(), "그룹명은 필수입니다.", GeneralErrorCode.BAD_REQUEST);
        validateLength(name, 100, "그룹명은 100자 이하여야 합니다.", GeneralErrorCode.BAD_REQUEST);
        if (groupRepository.existsByNameAndIdNot(name, groupId)) {
            throw new NotificationException(NotificationErrorCode.GROUP_DUPLICATE);
        }
        GroupPerspective perspective = request.getPerspective() == null
                ? group.getPerspective() : parsePerspective(request.getPerspective());
        boolean active = request.getActive() == null ? group.isActive() : request.getActive();
        group.update(name, perspective, active);
        return toGroup(group, false);
    }

    @Transactional
    public NotificationResDTO.GroupDeleted deleteGroup(Long groupId) {
        NotificationGroup group = findGroup(groupId, false);
        int removed = group.getMembers().size();
        group.getMembers().clear();
        groupRepository.delete(group);
        return NotificationResDTO.GroupDeleted.builder()
                .id(groupId)
                .deletedAt(toOffset(LocalDateTime.now(ApiTimeZone.ZONE)))
                .removedMemberCount(removed)
                .build();
    }

    @Transactional
    public NotificationResDTO.GroupMembers replaceGroupMembers(Long groupId,
                                                                NotificationReqDTO.MembersUpdate request) {
        NotificationGroup group = findGroup(groupId, false);
        Set<Long> before = group.getMembers().stream().map(NotificationRecipient::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<NotificationRecipient> members = findRecipients(request.getRecipientIds());
        Set<Long> after = members.stream().map(NotificationRecipient::getId)
                .collect(java.util.stream.Collectors.toSet());
        int added = (int) after.stream().filter(id -> !before.contains(id)).count();
        int removed = (int) before.stream().filter(id -> !after.contains(id)).count();
        group.replaceMembers(members);
        return NotificationResDTO.GroupMembers.builder()
                .groupId(groupId)
                .members(toMembers(group))
                .addedCount(added)
                .removedCount(removed)
                .memberCount(members.size())
                .activeMemberCount((int) members.stream().filter(NotificationRecipient::isActive).count())
                .build();
    }

    NotificationChannel findChannel(Long channelId, boolean requireActive) {
        NotificationChannel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.CHANNEL_NOT_FOUND));
        if (requireActive && !channel.isActive()) {
            throw new NotificationException(NotificationErrorCode.CHANNEL_NOT_FOUND);
        }
        return channel;
    }

    NotificationRecipient findRecipient(Long recipientId) {
        return recipientRepository.findById(recipientId)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.RECIPIENT_NOT_FOUND));
    }

    NotificationGroup findGroup(Long groupId, boolean requireActive) {
        NotificationGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.GROUP_NOT_FOUND));
        if (requireActive && !group.isActive()) {
            throw new NotificationException(NotificationErrorCode.GROUP_NOT_FOUND);
        }
        return group;
    }

    private List<RecipientDestination> buildDestinations(Long recipientId,
                                                         List<NotificationReqDTO.DestinationInput> inputs,
                                                         List<RecipientDestination> existing) {
        if (inputs == null) {
            return List.of();
        }
        Map<Long, RecipientDestination> oldByChannel = existing.stream()
                .collect(java.util.stream.Collectors.toMap(
                        destination -> destination.getChannel().getId(), destination -> destination));
        Set<Long> channelIds = new HashSet<>();
        List<RecipientDestination> destinations = new ArrayList<>();
        for (NotificationReqDTO.DestinationInput input : inputs) {
            if (input == null || input.getChannelId() == null || !channelIds.add(input.getChannelId())) {
                throw new NotificationException(NotificationErrorCode.RECIPIENT_INVALID,
                        "수신 채널은 한 번씩만 설정할 수 있습니다.");
            }
            NotificationChannel channel = findChannel(input.getChannelId(), false);
            boolean use = input.getUse() == null || input.getUse();
            String address = trimToNull(input.getAddress());
            if (use && !StringUtils.hasText(address)) {
                throw new NotificationException(NotificationErrorCode.RECIPIENT_INVALID,
                        "수신 주소 없이 해당 채널을 활성화할 수 없습니다.",
                        Map.of("channelId", channel.getId()));
            }
            if (StringUtils.hasText(address)) {
                boolean duplicate = recipientId == null
                        ? destinationRepository.existsByChannelIdAndAddress(channel.getId(), address)
                        : destinationRepository.existsByChannelIdAndAddressAndRecipientIdNot(
                                channel.getId(), address, recipientId);
                if (duplicate) {
                    throw new NotificationException(NotificationErrorCode.RECIPIENT_DUPLICATE,
                            Map.of("channelId", channel.getId(), "address", address));
                }
            }
            RecipientDestination old = oldByChannel.get(channel.getId());
            boolean onboarded = channel.getChannelType() == ChannelType.EMAIL
                    || old != null && java.util.Objects.equals(old.getAddress(), address) && old.isOnboarded();
            destinations.add(RecipientDestination.builder()
                    .channel(channel)
                    .address(address)
                    .use(use)
                    .onboarded(onboarded)
                    .build());
        }
        return destinations;
    }

    private List<NotificationRecipient> findRecipients(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NotificationException(NotificationErrorCode.RECIPIENT_INVALID,
                    "recipientIds에는 null을 포함할 수 없습니다.");
        }
        List<Long> distinct = ids.stream().distinct().toList();
        List<NotificationRecipient> recipients = recipientRepository.findAllById(distinct);
        Set<Long> found = recipients.stream().map(NotificationRecipient::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<Long> missing = distinct.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.RECIPIENT_NOT_FOUND,
                    Map.of("notFoundRecipientIds", missing));
        }
        Map<Long, NotificationRecipient> byId = recipients.stream()
                .collect(java.util.stream.Collectors.toMap(NotificationRecipient::getId, value -> value));
        return distinct.stream().map(byId::get).toList();
    }

    private NotificationResDTO.Channel toChannel(NotificationChannel channel, boolean includeConfigured) {
        return NotificationResDTO.Channel.builder()
                .id(channel.getId())
                .channelType(channel.getChannelType().name())
                .name(channel.getName())
                .config(safeConfig(channel.getConfig()))
                .maxLength(channel.getMaxLength())
                .active(channel.isActive())
                .tokenConfigured(includeConfigured
                        ? senderRegistry.get(channel.getChannelType()).isConfigured(channel) : null)
                .build();
    }

    private NotificationResDTO.Recipient toRecipient(NotificationRecipient recipient) {
        return toRecipient(recipient, true);
    }

    private NotificationResDTO.Recipient toRecipient(NotificationRecipient recipient, boolean includeGroups) {
        return NotificationResDTO.Recipient.builder()
                .id(recipient.getId())
                .name(recipient.getName())
                .phone(recipient.getPhone())
                .email(recipient.getEmail())
                .memo(recipient.getMemo())
                .active(recipient.isActive())
                .destinations(recipient.getDestinations().stream().map(this::toDestination).toList())
                .groupNames(includeGroups
                        ? recipient.getGroups().stream().map(NotificationGroup::getName).sorted().toList() : null)
                .build();
    }

    private NotificationResDTO.RecipientBasic toRecipientBasic(NotificationRecipient recipient) {
        return NotificationResDTO.RecipientBasic.builder()
                .id(recipient.getId()).name(recipient.getName()).phone(recipient.getPhone())
                .email(recipient.getEmail()).memo(recipient.getMemo()).active(recipient.isActive()).build();
    }

    private NotificationResDTO.Destination toDestination(RecipientDestination destination) {
        return NotificationResDTO.Destination.builder()
                .channelId(destination.getChannel().getId())
                .channelType(destination.getChannel().getChannelType().name())
                .address(destination.getAddress())
                .use(destination.isUse())
                .onboarded(destination.isOnboarded())
                .build();
    }

    private NotificationResDTO.Group toGroup(NotificationGroup group, boolean includeMembers) {
        return NotificationResDTO.Group.builder()
                .id(group.getId())
                .name(group.getName())
                .perspective(group.getPerspective() == null ? null : group.getPerspective().name())
                .active(group.isActive())
                .memberCount(group.getMembers().size())
                .activeMemberCount((int) group.getMembers().stream().filter(NotificationRecipient::isActive).count())
                .members(includeMembers ? toMembers(group) : null)
                .build();
    }

    private List<NotificationResDTO.GroupMember> toMembers(NotificationGroup group) {
        return group.getMembers().stream()
                .map(recipient -> NotificationResDTO.GroupMember.builder()
                        .recipientId(recipient.getId()).name(recipient.getName()).active(recipient.isActive()).build())
                .toList();
    }

    private void validateChannel(ChannelType type, Map<String, Object> config, int maxLength) {
        if (maxLength <= 0) {
            throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID, "maxLength는 1 이상이어야 합니다.");
        }
        if (type == ChannelType.TELEGRAM) {
            validateConfigKeys(config, Set.of("parseMode", "disableWebPagePreview"));
            if (!"HTML".equals(config.get("parseMode"))) {
                throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID,
                        "텔레그램 parseMode는 HTML만 지원합니다.");
            }
            if (maxLength > 4096) {
                throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID,
                        "텔레그램 maxLength는 4096보다 클 수 없습니다.");
            }
            if (config.containsKey("disableWebPagePreview")
                    && !(config.get("disableWebPagePreview") instanceof Boolean)) {
                throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID,
                        "disableWebPagePreview는 true 또는 false여야 합니다.");
            }
            return;
        }

        validateConfigKeys(config, Set.of("host", "port", "from", "ssl", "startTls"));
        if (!StringUtils.hasText(configText(config, "host"))) {
            throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID, "메일 host는 필수입니다.");
        }
        Object port = config.get("port");
        if (!(port instanceof Number number) || number.intValue() < 1 || number.intValue() > 65535) {
            throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID,
                    "메일 port는 1 이상 65535 이하여야 합니다.");
        }
        String from = configText(config, "from");
        if (!StringUtils.hasText(from) || !from.contains("@")) {
            throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID,
                    "메일 from은 올바른 메일 주소여야 합니다.");
        }
        for (String key : List.of("ssl", "startTls")) {
            if (config.containsKey(key) && !(config.get(key) instanceof Boolean)) {
                throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID,
                        key + "은 true 또는 false여야 합니다.");
            }
        }
    }

    private void validateConfigKeys(Map<String, Object> config, Set<String> allowed) {
        List<String> unsupported = config.keySet().stream().filter(key -> !allowed.contains(key)).sorted().toList();
        if (!unsupported.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.CHANNEL_INVALID,
                    "지원하지 않는 채널 설정 항목입니다: " + unsupported);
        }
    }

    private Map<String, Object> safeConfig(Map<String, Object> config) {
        Map<String, Object> safe = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            String normalized = key.replace("_", "").toLowerCase(java.util.Locale.ROOT);
            boolean sensitive = normalized.contains("token") || normalized.contains("password")
                    || normalized.contains("secret") || normalized.contains("apikey");
            if (!sensitive) {
                safe.put(key, value);
            }
        });
        return safe;
    }

    private String configText(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private ChannelType parseChannelType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return ChannelType.from(value);
        } catch (IllegalArgumentException exception) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "지원하지 않는 channelType 값입니다.");
        }
    }

    private GroupPerspective parsePerspective(String value) {
        try {
            return GroupPerspective.from(value);
        } catch (IllegalArgumentException exception) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "지원하지 않는 perspective 값입니다.");
        }
    }

    private String required(String value, String message, BaseErrorCode code) {
        if (!StringUtils.hasText(value)) {
            throw new GeneralException(code, message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void validateLength(String value, int max, String message,
                                BaseErrorCode code) {
        if (value.length() > max) {
            throw new GeneralException(code, message);
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (size < PageResponse.MIN_SIZE || size > PageResponse.MAX_SIZE) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "size는 1 이상 100 이하여야 합니다.");
        }
    }

    private java.time.OffsetDateTime toOffset(LocalDateTime value) {
        return value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }
}
