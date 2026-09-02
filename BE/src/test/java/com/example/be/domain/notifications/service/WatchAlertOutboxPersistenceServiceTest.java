package com.example.be.domain.notifications.service;

import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus;
import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchAlertOutboxPersistenceServiceTest {

    private final WatchAlertOutboxRepository repository = mock(WatchAlertOutboxRepository.class);
    private final WatchAlertOutboxPersistenceService service =
            new WatchAlertOutboxPersistenceService(repository);

    @Test
    void claimsPendingAlertBeforeReturningDetachedSnapshot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T12:00:00+09:00");
        WatchAlertOutbox alert = WatchAlertOutbox.builder()
                .id(60L)
                .watch(NewsWatch.builder().id(50L)
                        .issue(NewsIssue.builder().id(70L).build())
                        .build())
                .issueTitle("삼성전자 HBM4 증설")
                .firstSeenAt(now.minusHours(2))
                .followUpCount(1)
                .publisherCount(2)
                .queuedAt(now)
                .status(WatchAlertDeliveryStatus.PENDING)
                .attemptCount(0)
                .build();
        when(repository.findClaimable(any())).thenReturn(List.of(alert));

        List<WatchAlertOutboxPersistenceService.WatchAlertSnapshot> snapshots = service.claimPending();

        assertEquals(WatchAlertDeliveryStatus.PROCESSING, alert.getStatus());
        assertEquals(1, alert.getAttemptCount());
        assertEquals(60L, snapshots.getFirst().id());
        assertEquals(70L, snapshots.getFirst().issueId());
        assertEquals("2시간 전 속보 '삼성전자 HBM4 증설'에 후속 1건 · 매체 2곳 확인됨",
                snapshots.getFirst().message());
        verify(repository).flush();
    }
}
