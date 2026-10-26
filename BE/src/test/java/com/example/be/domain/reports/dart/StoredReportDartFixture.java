package com.example.be.domain.reports.dart;

import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

/** Real stored-state service with isolated persistence, shared by delivery rendering regressions. */
public final class StoredReportDartFixture {
    public static final Company COMPANY = new Company("00164779", "SK하이닉스", "000660");
    public static final String FILING_URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814001234";
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final ReportDartRepository attachments = mock(ReportDartRepository.class);
    private final ReportDartPersistence writes = mock(ReportDartPersistence.class);
    private final ReportDartService service = new ReportDartService(reports, attachments, writes, new DartProperties());

    public ReportDartService service() { return service; }

    public void ready(NewsReport report) { stored(report, "READY", financials()); }

    public void stored(NewsReport report, String status, FinancialData data) {
        when(attachments.find(report.getId())).thenReturn(List.of(new ReportDartRepository.Attachment(
                report.getId(), COMPANY, status, "공시 자료를 확인했습니다.", data)));
    }

    public void verifyStoredReads(long reportId, int count) {
        verify(attachments, times(count)).find(reportId);
        verifyNoMoreInteractions(attachments);
        verifyNoInteractions(reports, writes);
    }

    public void verifyNoPersistenceAccess() { verifyNoInteractions(reports, attachments, writes); }

    public static NewsReport topicWeekly() {
        return NewsReport.builder().id(17L).reportScope(ReportScope.WEEKLY).topicId(29L).topicName("SK하이닉스")
                .topicDartCorpCode(COMPANY.corpCode()).topicDartCorpName(COMPANY.corpName()).topicDartStockCode(COMPANY.stockCode())
                .reportStatus(ReportStatus.GENERATED).title("SK하이닉스 주간 보고서 · 가상 데이터")
                .reportDate(LocalDate.of(2026, 10, 12)).reportEndDate(LocalDate.of(2026, 10, 18))
                .generatedAt(LocalDateTime.of(2026, 10, 19, 9, 0)).markdownBody("").build();
    }

    public static FinancialData financials() {
        return new FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-14T16:30:00Z"), List.of(
                new Quarter(2026, 1, "10000000000", "5000000000", null, false, List.of()),
                new Quarter(2026, 2, "123456789012", "-1234567890", null, false, List.of())), List.of(
                new Filing("20260814001234", "반기보고서 (가상 검증 자료)", "2026-08-14", FILING_URL, true),
                new Filing("20260515001234", "이전 공시 (가상 검증 자료)", "2026-05-15",
                        "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260515001234", false)), List.of());
    }

    public static List<NewsReport> withoutEligibleCompany() {
        var history = List.of(new ReportCollectionContext(42L, List.of(new CollectionTopicSnapshot(
                29L, "SK하이닉스", null, List.of(), List.of(), List.of(), 10, 60, COMPANY))));
        return List.of(
                NewsReport.builder().id(18L).reportScope(ReportScope.DAILY).collectionContexts(history)
                        .reportStatus(ReportStatus.GENERATED).title("일일 통합 보고서").markdownBody("").build(),
                NewsReport.builder().id(19L).reportScope(ReportScope.WEEKLY).collectionContexts(history)
                        .reportStatus(ReportStatus.GENERATED).title("전체 주간 보고서").markdownBody("").build(),
                NewsReport.builder().id(20L).reportScope(ReportScope.WEEKLY).topicId(29L).collectionContexts(history)
                        .reportStatus(ReportStatus.GENERATED).title("회사 선택을 저장하지 않은 주제별 주간 보고서").markdownBody("").build(),
                NewsReport.builder().id(21L).reportScope(ReportScope.RUN)
                        .reportStatus(ReportStatus.GENERATED).title("회사 미선택 실행 보고서").markdownBody("").build());
    }
}
