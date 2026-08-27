package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.repository.NotificationGroupRepository;
import com.example.be.domain.notifications.repository.NotificationRecipientRepository;
import com.example.be.domain.notifications.repository.RecipientDestinationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationManagementServiceTest {

    private final NotificationChannelRepository channelRepository = mock(NotificationChannelRepository.class);
    private final NotificationRecipientRepository recipientRepository = mock(NotificationRecipientRepository.class);
    private final RecipientDestinationRepository destinationRepository = mock(RecipientDestinationRepository.class);
    private final NotificationGroupRepository groupRepository = mock(NotificationGroupRepository.class);
    private final NotificationSenderRegistry senderRegistry = mock(NotificationSenderRegistry.class);
    private final NotificationManagementService service = new NotificationManagementService(
            channelRepository, recipientRepository, destinationRepository, groupRepository, senderRegistry);

    @Test
    void telegramRejectsNonHtmlParseMode() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(NotificationChannel.builder()
                .id(1L).channelType(ChannelType.TELEGRAM).name("텔레그램")
                .config(Map.of("parseMode", "HTML")).maxLength(3500).active(true).build()));
        NotificationReqDTO.ChannelUpdate request = new NotificationReqDTO.ChannelUpdate();
        request.setConfig(Map.of("parseMode", "MarkdownV2"));

        NotificationException exception = assertThrows(NotificationException.class,
                () -> service.updateChannel(1L, request));

        assertEquals("CHANNEL400", exception.getCode().getCode());
        assertEquals("텔레그램 parseMode는 HTML만 지원합니다.", exception.getMessage());
    }

    @Test
    void telegramRejectsLengthAboveApiLimit() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(NotificationChannel.builder()
                .id(1L).channelType(ChannelType.TELEGRAM).name("텔레그램")
                .config(Map.of("parseMode", "HTML")).maxLength(3500).active(true).build()));
        NotificationReqDTO.ChannelUpdate request = new NotificationReqDTO.ChannelUpdate();
        request.setMaxLength(4097);

        NotificationException exception = assertThrows(NotificationException.class,
                () -> service.updateChannel(1L, request));

        assertEquals("텔레그램 maxLength는 4096보다 클 수 없습니다.", exception.getMessage());
    }

    @Test
    void channelConfigRejectsSecretFields() {
        when(channelRepository.findById(1L)).thenReturn(Optional.of(NotificationChannel.builder()
                .id(1L).channelType(ChannelType.TELEGRAM).name("텔레그램")
                .config(Map.of("parseMode", "HTML")).maxLength(3500).active(true).build()));
        NotificationReqDTO.ChannelUpdate request = new NotificationReqDTO.ChannelUpdate();
        request.setConfig(Map.of("parseMode", "HTML", "botToken", "must-not-be-persisted"));

        NotificationException exception = assertThrows(NotificationException.class,
                () -> service.updateChannel(1L, request));

        assertEquals("CHANNEL400", exception.getCode().getCode());
        assertEquals("지원하지 않는 채널 설정 항목입니다: [botToken]", exception.getMessage());
    }

    @Test
    void emailRejectsInvalidPort() {
        when(channelRepository.findById(2L)).thenReturn(Optional.of(NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일")
                .config(Map.of("host", "localhost", "port", 1025, "from", "news-agent@local.test"))
                .maxLength(Integer.MAX_VALUE).active(true).build()));
        NotificationReqDTO.ChannelUpdate request = new NotificationReqDTO.ChannelUpdate();
        request.setConfig(Map.of("host", "localhost", "port", 70000, "from", "news-agent@local.test"));

        NotificationException exception = assertThrows(NotificationException.class,
                () -> service.updateChannel(2L, request));

        assertEquals("메일 port는 1 이상 65535 이하여야 합니다.", exception.getMessage());
    }

    @Test
    void nullRecipientIdDoesNotClearExistingGroupMembers() {
        var existing = com.example.be.domain.notifications.entity.NotificationRecipient.builder()
                .id(7L).name("기존 멤버").active(true)
                .destinations(new ArrayList<>()).groups(new ArrayList<>()).build();
        var group = com.example.be.domain.notifications.entity.NotificationGroup.builder()
                .id(3L).name("기술").active(true).createdAt(LocalDateTime.now())
                .members(new ArrayList<>(List.of(existing))).build();
        when(groupRepository.findById(3L)).thenReturn(Optional.of(group));
        NotificationReqDTO.MembersUpdate request = new NotificationReqDTO.MembersUpdate();
        request.setRecipientIds(java.util.Arrays.asList((Long) null));

        NotificationException exception = assertThrows(NotificationException.class,
                () -> service.replaceGroupMembers(3L, request));

        assertEquals("RECIPIENT400", exception.getCode().getCode());
        assertEquals(List.of(existing), group.getMembers());
        verify(recipientRepository, never()).findAllById(org.mockito.ArgumentMatchers.any());
    }
}
