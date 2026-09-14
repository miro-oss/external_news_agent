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
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchAlertOutboxPersistenceServiceTest {

    private final WatchAlertOutboxRepository repository = mock(WatchAlertOutboxRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueStatusCalculator issueStatusCalculator = mock(IssueStatusCalculator.class);
    private final WatchAlertOutboxPersistenceService service =
            new WatchAlertOutboxPersistenceService(repository,
                    new WatchAlertOutboxBatchClaimer(repository, issueArticleRepository,
                            new BreakingNewsDetector(), issueStatusCalculator));

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
        stubCandidates(List.of(alert));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "[속보] 삼성전자 HBM4 증설", FetchStatus.FULLTEXT, "확보한 원문"),
                membership(2L, "후속 보도", FetchStatus.FULLTEXT, "확보한 후속 원문")));

        List<WatchAlertOutboxPersistenceService.WatchAlertSnapshot> snapshots = claimInitialBatch();

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
        stubCandidates(List.of(alert));
        IssueArticle visible = membership(1L, "HBM4 양산 전망", FetchStatus.FULLTEXT, "확보한 반박 원문");
        IssueArticle second = membership(2L, "후속 보도", FetchStatus.FULLTEXT, "후속 원문");
        IssueArticle third = membership(3L, "반박 보도", FetchStatus.FULLTEXT, "반박 원문");
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(71L)).thenReturn(List.of(visible, second, third));
        when(issueStatusCalculator.calculateFromFullText(alert.getWatch().getIssue(), List.of(visible, second, third)))
                .thenReturn(new IssueStatusCalculator.Projection(IssueStatus.DISPUTED, "본문 반박 확인"));

        var snapshot = claimInitialBatch().getFirst();

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
        stubCandidates(alerts);
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "메타데이터 제목", FetchStatus.METADATA_ONLY, "요약 복사"),
                membership(2L, "빈 본문 제목", FetchStatus.FULLTEXT, null),
                membership(3L, "공백 본문 제목", FetchStatus.FULLTEXT, " \n\t "),
                membership(4L, "다른 정상 제목", FetchStatus.FULLTEXT, "확보한 원문")));

        assertTrue(claimInitialBatch().isEmpty());

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
        stubCandidates(alerts);
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문"),
                membership(2L, "후속 보도", FetchStatus.FULLTEXT, "확보한 후속 원문")));

        LocalDateTime now = LocalDateTime.now();
        var firstBatchResult = service.claimNextBatch(0, now, 100);
        assertTrue(firstBatchResult.snapshots().isEmpty());
        assertEquals(100L, firstBatchResult.afterId());
        var snapshots = service.claimNextBatch(firstBatchResult.afterId(), now, 100).snapshots();

        assertEquals(List.of(101L), snapshots.stream()
                .map(WatchAlertOutboxPersistenceService.WatchAlertSnapshot::id).toList());
        assertEquals(WatchAlertDeliveryStatus.PROCESSING, available.getStatus());
        assertEquals(WatchAlertDeliveryStatus.PENDING, alerts.getFirst().getStatus());
        assertEquals(0, alerts.getFirst().getAttemptCount());
        verify(repository).findClaimableIds(any(), eq(0L), any());
        verify(repository).findClaimableIds(any(), eq(100L), any());
        verify(repository).findClaimableByIdsForUpdate(eq(List.of(101L)), any());
        verify(issueArticleRepository, times(2)).findByIssueIdOrderByJoinedAtAsc(70L);
    }

    @Test
    void stopsReadingCandidatesOnceOneHundredVisibleAlertsAreClaimed() {
        List<WatchAlertOutbox> alerts = new ArrayList<>();
        for (long id = 1; id <= 250; id++) {
            alerts.add(alert(id, "확보한 기사 제목", WatchType.BREAKING));
        }
        stubCandidates(alerts);
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문"),
                membership(2L, "후속 보도", FetchStatus.FULLTEXT, "확보한 후속 원문")));

        var snapshots = claimInitialBatch();

        assertEquals(alerts.subList(0, 100).stream().map(WatchAlertOutbox::getId).toList(),
                snapshots.stream().map(WatchAlertOutboxPersistenceService.WatchAlertSnapshot::id).toList());
        assertEquals(WatchAlertDeliveryStatus.PENDING, alerts.get(100).getStatus());
        assertEquals(0, alerts.get(100).getAttemptCount());
        verify(repository, times(1)).findClaimableIds(any(), anyLong(), any());
        verify(repository, times(1)).findClaimableByIdsForUpdate(anyList(), any());
    }

    @Test
    void advancesPastCandidatesClaimedByAnotherWorkerBeforeTheLockQuery() {
        List<WatchAlertOutbox> alerts = new ArrayList<>();
        for (long id = 1; id <= 101; id++) {
            alerts.add(alert(id, "확보한 기사 제목", WatchType.BREAKING));
        }
        stubCandidates(alerts);
        List<Long> firstBatch = alerts.subList(0, 100).stream().map(WatchAlertOutbox::getId).toList();
        when(repository.findClaimableByIdsForUpdate(eq(firstBatch), any())).thenReturn(List.of());
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문"),
                membership(2L, "후속 보도", FetchStatus.FULLTEXT, "확보한 후속 원문")));

        LocalDateTime now = LocalDateTime.now();
        var firstBatchResult = service.claimNextBatch(0, now, 100);
        assertTrue(firstBatchResult.snapshots().isEmpty());
        assertEquals(100L, firstBatchResult.afterId());
        var snapshots = service.claimNextBatch(firstBatchResult.afterId(), now, 100).snapshots();

        assertEquals(List.of(101L), snapshots.stream()
                .map(WatchAlertOutboxPersistenceService.WatchAlertSnapshot::id).toList());
        verify(repository).findClaimableIds(any(), eq(100L), any());
        assertEquals(WatchAlertDeliveryStatus.PENDING, alerts.getFirst().getStatus());
    }

    @Test
    void availableTitleDoesNotExposeDisputeSupportedOnlyByHiddenArticle() {
        WatchAlertOutbox alert = alert(1L, "확보한 기사 제목", WatchType.DISPUTED);
        IssueArticle visible = membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문");
        IssueArticle second = membership(3L, "후속 보도", FetchStatus.FULLTEXT, "확보한 후속 원문");
        IssueArticle hiddenDispute = membership(2L, "본문 없는 반박 제목", FetchStatus.METADATA_ONLY, null);
        hiddenDispute.applyStance(IssueStance.DISPUTES, IssueStanceSource.LLM, java.math.BigDecimal.ONE);
        stubCandidates(List.of(alert));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L))
                .thenReturn(List.of(visible, hiddenDispute, second));
        when(issueStatusCalculator.calculateFromFullText(alert.getWatch().getIssue(), List.of(visible, second)))
                .thenReturn(new IssueStatusCalculator.Projection(IssueStatus.EMERGING, "본문 반박 없음"));

        assertTrue(claimInitialBatch().isEmpty());

        verify(issueStatusCalculator).calculateFromFullText(alert.getWatch().getIssue(), List.of(visible, second));
        assertEquals(WatchAlertDeliveryStatus.PENDING, alert.getStatus());
        assertEquals("확보한 기사 제목", alert.getIssueTitle());
        assertEquals(0, alert.getAttemptCount());
        assertEquals(IssueStance.DISPUTES, hiddenDispute.getStance());
    }

    @Test
    void singleVisibleArticleCannotConfirmTheSavedFollowUpCount() {
        WatchAlertOutbox alert = alert(1L, "확보한 기사 제목", WatchType.BREAKING);
        stubCandidates(List.of(alert));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L)).thenReturn(List.of(
                membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문"),
                membership(2L, "숨긴 후속", FetchStatus.METADATA_ONLY, null)));

        assertTrue(claimInitialBatch().isEmpty());

        assertEquals(WatchAlertDeliveryStatus.PENDING, alert.getStatus());
        assertEquals(0, alert.getAttemptCount());
        assertEquals(1, alert.getFollowUpCount());
    }

    @Test
    void capsDisplayedLegacyCountsWithoutChangingStoredOutbox() {
        WatchAlertOutbox original = alert(1L, "확보한 기사 제목", WatchType.BREAKING);
        WatchAlertOutbox alert = WatchAlertOutbox.builder().id(1L).watch(original.getWatch())
                .issueTitle(original.getIssueTitle()).firstSeenAt(original.getFirstSeenAt())
                .queuedAt(original.getQueuedAt()).followUpCount(8).publisherCount(9)
                .status(WatchAlertDeliveryStatus.PENDING).attemptCount(0).build();
        stubCandidates(List.of(alert));
        IssueArticle first = membership(1L, "확보한 기사 제목", FetchStatus.FULLTEXT, "확보한 원문");
        IssueArticle samePublisher = IssueArticle.builder().id(2L)
                .article(Article.builder().id(2L).title("후속 보도").body("확보한 후속 원문")
                        .fetchStatus(FetchStatus.FULLTEXT).sourceName("  매체1  ").build()).build();
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(70L))
                .thenReturn(List.of(first, samePublisher, membership(3L, "숨긴 후속", FetchStatus.METADATA_ONLY, null)));

        var snapshot = claimInitialBatch().getFirst();

        assertEquals(1, snapshot.followUpCount());
        assertEquals(1, snapshot.publisherCount());
        assertEquals(8, alert.getFollowUpCount());
        assertEquals(9, alert.getPublisherCount());
        assertEquals(original.getIssueTitle(), alert.getIssueTitle());
    }

    private List<WatchAlertOutboxPersistenceService.WatchAlertSnapshot> claimInitialBatch() {
        return service.claimNextBatch(0, LocalDateTime.now(), 100).snapshots();
    }

    private WatchAlertOutbox alert(Long id, String title, WatchType type) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-14T12:00:00+09:00");
        return WatchAlertOutbox.builder().id(id)
                .watch(NewsWatch.builder().id(50L).watchType(type)
                        .issue(NewsIssue.builder().id(70L).build()).build())
                .issueTitle(title).firstSeenAt(now.minusHours(2)).followUpCount(1).publisherCount(2)
                .queuedAt(now).status(WatchAlertDeliveryStatus.PENDING).attemptCount(0).build();
    }

    private void stubCandidates(List<WatchAlertOutbox> alerts) {
        when(repository.findClaimableIds(any(), anyLong(), any())).thenAnswer(invocation -> {
            long afterId = invocation.getArgument(1);
            Pageable page = invocation.getArgument(2);
            assertEquals(0, page.getOffset());
            assertEquals(100, page.getPageSize());
            return alerts.stream().map(WatchAlertOutbox::getId).filter(id -> id > afterId)
                    .limit(page.getPageSize()).toList();
        });
        when(repository.findClaimableByIdsForUpdate(anyList(), any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            assertTrue(ids.size() <= 100);
            return alerts.stream().filter(alert -> ids.contains(alert.getId())).toList();
        });
    }

    private IssueArticle membership(Long id, String title, FetchStatus status, String body) {
        return IssueArticle.builder().id(id)
                .article(Article.builder().id(id).title(title).fetchStatus(status).body(body)
                        .sourceName("매체" + id).build())
                .stance(IssueStance.SUPPORTS).stanceSource(IssueStanceSource.LLM)
                .stanceConfidence(java.math.BigDecimal.ONE).build();
    }
}
