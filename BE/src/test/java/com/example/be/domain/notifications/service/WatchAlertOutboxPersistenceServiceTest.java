package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.cluster.BreakingNewsDetector;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.service.IssueStatusCalculator;
import com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus;
import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchAlertOutboxPersistenceServiceTest {

    private final WatchAlertOutboxRepository repository = mock(WatchAlertOutboxRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueStatusCalculator issueStatusCalculator = mock(IssueStatusCalculator.class);
    private final WatchAlertOutboxPersistenceService service =
            new WatchAlertOutboxPersistenceService(repository, issueArticleRepository,
                    new BreakingNewsDetector(), issueStatusCalculator);

    @Test
    void claimsPendingAlertBeforeReturningDetachedSnapshot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T12:00:00+09:00");
        WatchAlertOutbox alert = WatchAlertOutbox.builder()
                .id(60L)
                .watch(NewsWatch.builder().id(50L)
                        .watchType(WatchType.BREAKING)
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
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "[속보] 삼성전자 HBM4 증설", FetchStatus.FULLTEXT, "확보한 원문")));

        List<WatchAlertOutboxPersistenceService.WatchAlertSnapshot> snapshots = service.claimPending();

        assertEquals(WatchAlertDeliveryStatus.PROCESSING, alert.getStatus());
        assertEquals(1, alert.getAttemptCount());
        assertEquals(60L, snapshots.getFirst().id());
        assertEquals(70L, snapshots.getFirst().issueId());
        assertEquals("2시간 전 속보 '삼성전자 HBM4 증설'에 후속 1건 · 매체 2곳 확인됨",
                snapshots.getFirst().message());
        verify(repository).flush();
    }

    @Test
    void rendersDisputedWatchAsRefutationAlert() {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-02T12:00:00+09:00");
        WatchAlertOutbox alert = WatchAlertOutbox.builder()
                .id(61L)
                .watch(NewsWatch.builder().id(51L)
                        .watchType(WatchType.DISPUTED)
                        .issue(NewsIssue.builder().id(71L).build())
                        .build())
                .issueTitle("HBM4 양산 전망")
                .firstSeenAt(now.minusHours(4))
                .followUpCount(2)
                .publisherCount(3)
                .queuedAt(now)
                .status(WatchAlertDeliveryStatus.PENDING)
                .attemptCount(0)
                .build();
        when(repository.findClaimable(any())).thenReturn(List.of(alert));
        IssueArticle visible = membership(1L, "HBM4 양산 전망", FetchStatus.FULLTEXT, "확보한 반박 원문");
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(71L)).thenReturn(List.of(visible));
        when(issueStatusCalculator.calculateFromFullText(alert.getWatch().getIssue(), List.of(visible)))
                .thenReturn(new IssueStatusCalculator.Projection(IssueStatus.DISPUTED, "본문 반박 확인"));

        var snapshot = service.claimPending().getFirst();

        assertEquals("⚠ 'HBM4 양산 전망'에 반박 기사 등장 · 후속 2건 · 매체 3곳 확인됨",
                snapshot.message());
    }

    @Test
    void unavailableOrUnverifiablePendingTitlesStayUnchangedAndAreNotClaimed() {
        List<WatchAlertOutbox> alerts = List.of(
                alert(1L, "메타데이터 제목", WatchType.BREAKING),
                alert(2L, "빈 본문 제목", WatchType.BREAKING),
                alert(3L, "공백 본문 제목", WatchType.BREAKING),
                alert(4L, "현재 출처가 없는 과거 제목", WatchType.BREAKING));
        when(repository.findClaimable(any())).thenReturn(alerts);
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "메타데이터 제목", FetchStatus.METADATA_ONLY, "요약 복사"),
                membership(2L, "빈 본문 제목", FetchStatus.FULLTEXT, null),
                membership(3L, "공백 본문 제목", FetchStatus.FULLTEXT, " \n\t "),
                membership(4L, "다른 정상 제목", FetchStatus.FULLTEXT, "확보한 원문")));

        assertTrue(service.claimPending().isEmpty());

        for (WatchAlertOutbox alert : alerts) {
            assertEquals(WatchAlertDeliveryStatus.PENDING, alert.getStatus());
            assertEquals(0, alert.getAttemptCount());
            assertNull(alert.getProcessingStartedAt());
            assertEquals(1, alert.getFollowUpCount());
            assertEquals(2, alert.getPublisherCount());
        }
        assertEquals(List.of("메타데이터 제목", "빈 본문 제목", "공백 본문 제목", "현재 출처가 없는 과거 제목"),
                alerts.stream().map(WatchAlertOutbox::getIssueTitle).toList());
        verify(issueArticleRepository, times(1)).findByIssueIdOrderByJoinedAtAsc(70L);
    }

    @Test
    void unavailableFirstHundredAlertsDoNotStarveLaterVisibleAlert() {
        List<WatchAlertOutbox> alerts = new ArrayList<>();
        for (int id = 1; id <= 100; id++) {
            alerts.add(alert((long) id, "검증 불가 과거 제목 " + id, WatchType.BREAKING));
        }
        WatchAlertOutbox available = alert(101L, "확보한 기사 제목", WatchType.BREAKING);
        alerts.add(available);
        when(repository.findClaimable(any())).thenReturn(alerts);
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문")));

        var snapshots = service.claimPending();

        assertEquals(List.of(101L), snapshots.stream()
                .map(WatchAlertOutboxPersistenceService.WatchAlertSnapshot::id).toList());
        assertEquals(WatchAlertDeliveryStatus.PROCESSING, available.getStatus());
        assertEquals(WatchAlertDeliveryStatus.PENDING, alerts.getFirst().getStatus());
        assertEquals(0, alerts.getFirst().getAttemptCount());
        verify(issueArticleRepository, times(1)).findByIssueIdOrderByJoinedAtAsc(70L);
    }

    @Test
    void availableTitleDoesNotExposeDisputeSupportedOnlyByHiddenArticle() {
        WatchAlertOutbox alert = alert(1L, "확보한 기사 제목", WatchType.DISPUTED);
        IssueArticle visible = membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문");
        IssueArticle hiddenDispute = membership(2L, "본문 없는 반박 제목", FetchStatus.METADATA_ONLY, null);
        hiddenDispute.applyStance(IssueStance.DISPUTES, IssueStanceSource.LLM, java.math.BigDecimal.ONE);
        when(repository.findClaimable(any())).thenReturn(List.of(alert));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L))
                .thenReturn(List.of(visible, hiddenDispute));
        when(issueStatusCalculator.calculateFromFullText(alert.getWatch().getIssue(), List.of(visible)))
                .thenReturn(new IssueStatusCalculator.Projection(IssueStatus.EMERGING, "본문 반박 없음"));

        assertTrue(service.claimPending().isEmpty());

        verify(issueStatusCalculator).calculateFromFullText(alert.getWatch().getIssue(), List.of(visible));
        assertEquals(WatchAlertDeliveryStatus.PENDING, alert.getStatus());
        assertEquals("확보한 기사 제목", alert.getIssueTitle());
        assertEquals(0, alert.getAttemptCount());
        assertEquals(IssueStance.DISPUTES, hiddenDispute.getStance());
    }

    private WatchAlertOutbox alert(Long id, String title, WatchType type) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-14T12:00:00+09:00");
        return WatchAlertOutbox.builder().id(id)
                .watch(NewsWatch.builder().id(50L).watchType(type)
                        .issue(NewsIssue.builder().id(70L).build()).build())
                .issueTitle(title).firstSeenAt(now.minusHours(2)).followUpCount(1).publisherCount(2)
                .queuedAt(now).status(WatchAlertDeliveryStatus.PENDING).attemptCount(0).build();
    }

    private IssueArticle membership(Long id, String title, FetchStatus status, String body) {
        return IssueArticle.builder().id(id)
                .article(Article.builder().id(id).title(title).fetchStatus(status).body(body).build())
                .stance(IssueStance.SUPPORTS).stanceSource(IssueStanceSource.LLM)
                .stanceConfidence(java.math.BigDecimal.ONE).build();
    }
}
