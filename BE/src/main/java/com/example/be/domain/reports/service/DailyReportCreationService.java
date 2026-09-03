package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportCreationService {

    private final DailyReportJdbcRepository dailyRepository;
    private final NewsReportRepository reportRepository;
    private final FindingRepository findingRepository;
    private final DailyReportSelector selector;
    private final DailyReportPersistenceService dailyPersistence;
    private final ReportPersistenceService persistence;
    private final AgentReportOrchestrator orchestrator;
    private final ReportGenerator fallbackGenerator;

    @Value("${news.reports.daily.max-issues:10}")
    private int maxIssues = 10;

    public Long generate(LocalDate date) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        if (!date.isBefore(now.toLocalDate())) {
            throw new IllegalArgumentException("일일 보고서는 종료된 날짜만 집계할 수 있습니다.");
        }
        NewsReport existing = reportRepository.findByReportScopeAndReportDate(ReportScope.DAILY, date)
                .orElse(null);
        if (existing != null) {
            return existing.getId();
        }
        // 자정을 걸쳐 실행 중인 run이 있으면 그 실행까지 완료된 뒤 집계한다.
        if (!dailyRepository.findDueDates(date, date.plusDays(1)).contains(date)) {
            return null;
        }
        List<Long> sourceRunIds = dailyRepository.findSourceRunIds(date);
        List<Finding> selected = selector.select(date, maxIssues);
        ReportSourceStats stats = dailyRepository.sourceStats(date);
        ReportPersistenceService.Reservation reservation = dailyPersistence.reserve(
                date, sourceRunIds, selected.stream().map(Finding::getId).toList(), now);
        if (!reservation.owner()) {
            return reservation.reportId();
        }
        ReportDocument document;
        try {
            document = orchestrator.generateDaily(reservation.reportId(), date, selected, stats, now);
        } catch (RuntimeException exception) {
            log.error("일일 보고서 생성 실패. date={}", date, exception);
            document = fallbackGenerator.generate(selected, now, stats);
        }
        return persistence.complete(reservation.reportId(), dailyDocument(date, sourceRunIds, document), now);
    }

    /** 예약 뒤 프로세스가 중단되면 저장된 근거로 복구한다. 불확실한 LLM 호출을 반복하지 않는다. */
    public void recoverInterrupted(LocalDateTime before) {
        for (NewsReport report : reportRepository.findByReportScopeAndReportStatusAndGeneratedAtBefore(
                ReportScope.DAILY, ReportStatus.PENDING, before)) {
            try {
                List<Finding> findings = ReportFindings.load(report, findingRepository);
                LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
                ReportDocument fallback = fallbackGenerator.generate(findings, now,
                        dailyRepository.sourceStats(report.getReportDate()));
                persistence.complete(report.getId(),
                        dailyDocument(report.getReportDate(), report.getSourceRunIds(), fallback), now);
            } catch (RuntimeException exception) {
                log.error("중단된 일일 보고서 복구 실패. reportId={}", report.getId(), exception);
            }
        }
    }

    private ReportDocument dailyDocument(LocalDate date, List<Long> runIds, ReportDocument document) {
        String title = date + " 일일 통합 뉴스 보고서";
        String body = document.markdownBody().replaceFirst("^# [^\\n]*\\n", "")
                .replace("이번 실행", "집계일");
        String markdown = "# " + title + "\n\n" + "한국 시간 " + date + " · 수집 " + runIds.size()
                + "회 · 주요 이슈 " + document.reflectedFindingIds().size() + "건\n\n" + body.strip();
        return new ReportDocument(title, markdown, document.modelName(), document.promptVersion(),
                document.llmProvider(), document.inputTokens(), document.outputTokens(), document.costUsd(),
                document.credits(), document.status(), document.reflectedFindingIds(), document.excludedFindingIds());
    }
}
