package com.example.be.domain.reports.dart;

import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportDartServiceTest {
    final NewsReportRepository reports = mock(NewsReportRepository.class);
    final ReportDartRepository repository = mock(ReportDartRepository.class);
    final ReportDartPersistence persistence = mock(ReportDartPersistence.class);
    final DartProperties properties = new DartProperties();
    final com.example.be.domain.analysis.repository.FindingRepository findings = mock(com.example.be.domain.analysis.repository.FindingRepository.class);
    final com.example.be.domain.analysis.relevance.TopicRelevancePolicy relevance = mock(com.example.be.domain.analysis.relevance.TopicRelevancePolicy.class);
    final com.example.be.domain.reports.service.ReportEventFeedbackProjection feedback = mock(com.example.be.domain.reports.service.ReportEventFeedbackProjection.class);
    final ReportDartService service = new ReportDartService(reports, repository, persistence, properties, findings, relevance, feedback);

    @Test void readsOnlyFrozenCompaniesInSnapshotOrderAndDeduplicatesCodes() {
        properties.setApiKey("unit-test-key");
        var report = report(ReportScope.RUN, HYNIX, SAMSUNG, HYNIX);
        visibleTitle(report, "SK하이닉스와 삼성전자 실적 발표");
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L,
                new Company(SAMSUNG.corpCode(), "지금 바뀐 회사명", SAMSUNG.stockCode()), "READY", ReportDartMessages.READY, data())));
        var result = service.get(17L);
        assertEquals(List.of(HYNIX, SAMSUNG), result.companies().stream().map(ReportDartDTO.CompanyAttachment::company).toList());
        assertEquals("PENDING", result.companies().getFirst().status());
        assertEquals("READY", result.companies().getLast().status());
        assertEquals("10000000000000001", result.companies().getLast().data().quarters().getFirst().revenue());
        verifyNoInteractions(persistence);
        verify(repository, never()).enqueue(anyLong(), any(), anyBoolean(), any());
    }

    @Test void legacyUnselectedDailyAndWeeklyReportsHaveNoPanelEvenIfKeywordsMentionACompany() {
        for (var report : List.of(report(ReportScope.RUN, (Company) null), report(ReportScope.DAILY, SAMSUNG),
                report(ReportScope.WEEKLY, SAMSUNG), report(ReportScope.RUN))) {
            when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
            assertTrue(service.get(17L).companies().isEmpty());
        }
        verifyNoInteractions(repository, persistence);
    }

    @Test void topicWeeklyUsesReservationCompanyInsteadOfHistoricalCollectionCompanies() {
        var report = topicWeekly(SAMSUNG, ReportStatus.GENERATED);
        visibleTitle(report, "삼성전자의 실적 발표");
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(repository.find(17L)).thenReturn(List.of(
                new ReportDartRepository.Attachment(17L, SAMSUNG, "READY", ReportDartMessages.READY, data()),
                new ReportDartRepository.Attachment(17L, HYNIX, "READY", ReportDartMessages.READY, data())));

        var result = service.get(17L);

        assertEquals(List.of(SAMSUNG), result.companies().stream().map(ReportDartDTO.CompanyAttachment::company).toList());
        assertEquals(data(), result.companies().getFirst().data());
        verifyNoInteractions(persistence);
    }

    @Test void legacyPendingAndHiddenTopicReportsNeverExposeHistoricalCompanies() {
        var hidden = topicWeekly(SAMSUNG, ReportStatus.GENERATED);
        hidden.hide(java.time.LocalDateTime.now());
        for (var report : List.of(topicWeekly(null, ReportStatus.GENERATED),
                topicWeekly(SAMSUNG, ReportStatus.PENDING), hidden)) {
            assertTrue(service.getStored(report).companies().isEmpty());
        }
        verifyNoInteractions(repository, persistence);
    }

    @Test void missingKeyReturnsDisabledWithoutBlockingReportAndRunningMapsToPending() {
        var report = report(ReportScope.RUN, SAMSUNG);
        visibleTitle(report, "삼성전자 실적 발표");
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        assertEquals("DISABLED", service.get(17L).companies().getFirst().status());
        assertEquals("DART API 키가 설정되지 않았습니다.", service.get(17L).companies().getFirst().message());
        properties.setApiKey("unit-test-key");
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L, SAMSUNG,
                "RUNNING", ReportDartMessages.PENDING, null)));
        assertEquals("PENDING", service.get(17L).companies().getFirst().status());
    }

    @Test void retryReturnsStoredCompletedDataWithoutReplacingIt() {
        var report = report(ReportScope.RUN, SAMSUNG);
        visibleTitle(report, "삼성전자 실적 발표");
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L, SAMSUNG,
                "READY", ReportDartMessages.READY, data())));
        assertEquals(data(), service.retry(17L).companies().getFirst().data());
        verify(persistence).retry(17L);
        verify(repository, never()).complete(any(), anyString(), any(), any());
    }

    @Test void absentPendingAndHiddenReportsUseExistingNotFoundContract() {
        assertThrows(ReportException.class, () -> service.get(404L));
    }

    @Test void publicReadHidesUnmentionedSelectionWithoutChangingStoredAttachmentOrStartingWork() {
        var report = report(ReportScope.RUN, SAMSUNG);
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L, SAMSUNG,
                "READY", ReportDartMessages.READY, data())));
        visibleTitle(report, "행안부 공공 시스템 점검");

        assertTrue(service.get(17L).companies().isEmpty());
        assertEquals(data(), service.getStored(report).companies().getFirst().data());
        visibleTitle(report, "삼성전자 실적 발표");
        assertEquals(SAMSUNG, service.get(17L).companies().getFirst().company());
        when(feedback.project(eq(report), any(), any())).thenReturn(new ReportEventFeedbackProjection.View(
                List.of(), new ReportReadingContent("삼성전자 실적", null), false));
        assertTrue(service.get(17L).companies().isEmpty(), "Legacy markdown alone cannot qualify a company");
        verifyNoInteractions(persistence);
        verify(repository, never()).enqueue(anyLong(), any(), anyBoolean(), any());
    }

    @Test void legacyUnlistedSelectionsNeverReadAttachmentsAndMixedSelectionsKeepListedCompanies() {
        var unlisted = new Company("99999001", "비상장기업", "");
        var legacyNullStock = new Company("99999003", "종목코드없음", null);
        for (var report : List.of(report(ReportScope.RUN, unlisted), topicWeekly(unlisted, ReportStatus.GENERATED),
                report(ReportScope.RUN, legacyNullStock), topicWeekly(legacyNullStock, ReportStatus.GENERATED))) {
            assertTrue(service.getStored(report).companies().isEmpty());
        }
        verifyNoInteractions(repository);

        var listed = new Company("99999002", "신규상장기업", "0068Y0");
        var result = service.getStored(report(ReportScope.RUN, unlisted, SAMSUNG, listed));
        assertEquals(List.of(SAMSUNG, listed), result.companies().stream().map(ReportDartDTO.CompanyAttachment::company).toList());
        verify(repository).find(17L);
    }

    @Test void notificationReadsExistingReportSnapshotWithoutReloadingOrEnqueueing() {
        var report = report(ReportScope.RUN, SAMSUNG, SAMSUNG);
        when(repository.find(17L)).thenReturn(List.of(
                new ReportDartRepository.Attachment(17L, SAMSUNG, "READY", ReportDartMessages.READY, data()),
                new ReportDartRepository.Attachment(17L, HYNIX, "READY", ReportDartMessages.READY, data())));

        var result = service.getStored(report);

        assertEquals(1, result.companies().size());
        assertEquals(SAMSUNG, result.companies().getFirst().company());
        assertEquals(data(), result.companies().getFirst().data());
        verifyNoInteractions(reports, persistence);
        verify(repository).find(17L);
        verifyNoMoreInteractions(repository);
    }

    @Test void aggregatePublicReadRemovesHiddenCompanyAndRetryKeepsTheSameFrozenRawSnapshot() {
        var link = new ReportIssueCompanySnapshot.Link(SAMSUNG, 51L, 61L, 71L, "삼성전자",
                "삼성전자 공시", "https://example.com/article", List.of("20261029000001"));
        var snapshot = new ReportIssueCompanySnapshot("report-issue-companies.v1", java.time.OffsetDateTime.now(), List.of(link));
        var report = com.example.be.domain.reports.entity.NewsReport.builder().id(17L).reportScope(ReportScope.DAILY)
                .reportStatus(ReportStatus.GENERATED).reportIssueCompanySnapshot(snapshot).build();
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(repository.find(17L)).thenReturn(List.of(new ReportDartRepository.Attachment(17L, SAMSUNG,
                "READY", ReportDartMessages.READY, data())));
        var hidden = new com.example.be.domain.reports.service.ReportEventFeedbackProjection.View(List.of(),
                new com.example.be.domain.reports.service.ReportReadingContent("", null), true, java.util.Set.of(51L), true);
        when(feedback.project(eq(report), any(), any())).thenReturn(hidden);

        assertTrue(service.get(17L).companies().isEmpty());
        assertTrue(service.retry(17L).companies().isEmpty());
        assertEquals(1, service.getStored(report).companies().getFirst().issueLinks().size());
        assertSame(snapshot, report.getReportIssueCompanySnapshot());
        verify(persistence).retry(17L);
        verify(repository, never()).enqueue(anyLong(), any(), anyBoolean(), any());
    }

    private void visibleTitle(NewsReport report, String title) {
        var finding = com.example.be.domain.analysis.entity.Finding.builder().id(1L)
                .article(com.example.be.domain.collection.entity.Article.builder().id(101L).build()).build();
        var content = new ReportContent(List.of(), List.of(new ReportContent.ImportantEvent(
                title, "저장된 이슈 요약", "", List.of(1L))), List.of(), List.of());
        when(feedback.project(eq(report), any(), any())).thenReturn(new ReportEventFeedbackProjection.View(
                List.of(finding), new ReportReadingContent("", content), false));
    }
}
