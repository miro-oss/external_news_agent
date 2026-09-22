package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecipientReportSubscriptionServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationManagementService management = mock(NotificationManagementService.class);
    private final TopicRepository topics = mock(TopicRepository.class);
    private final RecipientReportSubscriptionService service = new RecipientReportSubscriptionService(jdbc, management, topics);

    @BeforeEach
    void existingRecipientAndTopic() {
        when(management.findRecipient(2L)).thenReturn(NotificationRecipient.builder().id(2L).active(true).build());
        when(topics.findById(1L)).thenReturn(Optional.of(Topic.builder().id(1L).name("주제").build()));
    }

    @Test
    void rejectsMissingNullUnknownAndOversizedExclusionsBeforeAnyWrite() {
        List<RecipientReportSubscriptionService.Exclusions> invalid = Arrays.asList(null,
                new RecipientReportSubscriptionService.Exclusions(null),
                new RecipientReportSubscriptionService.Exclusions(Arrays.asList("RUN", null)),
                new RecipientReportSubscriptionService.Exclusions(List.of("MONTHLY")),
                new RecipientReportSubscriptionService.Exclusions(List.of("RUN", "RUN", "RUN", "RUN")));
        for (var request : invalid) assertThatThrownBy(() -> service.save(2L, 1L, request))
                .isInstanceOf(GeneralException.class).hasMessage("제외할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요.");
        verifyNoInteractions(jdbc);
    }

    @Test
    void deduplicatesScopesAndCanRestoreWithoutGrantingANewSubscription() {
        var result = service.save(2L, 1L, new RecipientReportSubscriptionService.Exclusions(List.of("WEEKLY", "RUN", "WEEKLY")));
        assertThat(result.excludedScopes()).containsExactly("RUN", "WEEKLY");
        assertThat(result.enabled()).isFalse();
        assertThat(result.configuredScopes()).isEmpty();
        verify(jdbc, times(1)).update("INSERT INTO recipient_report_exclusions(recipient_id,topic_id,report_scope) VALUES(?,?,?)", 2L, 1L, "WEEKLY");
        var restored = service.save(2L, 1L, new RecipientReportSubscriptionService.Exclusions(List.of()));
        assertThat(restored.excludedScopes()).isEmpty();
        assertThat(restored.configuredScopes()).isEmpty();
        verify(jdbc, times(2)).update("DELETE FROM recipient_report_exclusions WHERE recipient_id=? AND topic_id=?", 2L, 1L);
    }
}
