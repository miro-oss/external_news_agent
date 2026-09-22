package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportCreationService {
    private final WeeklyReportPersistenceService reservationService;
    private final NewsReportRepository reports;
    private final AgentWeeklyReportOrchestrator orchestrator;
    private final WeeklyReportGenerator fallback;
    private final ReportPersistenceService persistence;

    public Long generate(LocalDate monday) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        var reservation = reservationService.reserve(monday, now);
        if (!reservation.owner()) return reservation.reportId();
        ReportDocument document;
        try {
            document = orchestrator.generate(reservation.reportId(), reservation.input(), now);
        } catch (RuntimeException exception) {
            log.error("주간 보고서 생성 실패. monday={}", monday, exception);
            document = fallback.generate(reservation.input());
        }
        return persistence.complete(reservation.reportId(), document, LocalDateTime.now(ApiTimeZone.ZONE));
    }

    /** Saved DAILY contents suffice for recovery; an uncertain provider call is never repeated. */
    public void recoverInterrupted(LocalDateTime before) {
        for (var report : reports.findByReportScopeAndReportStatusAndGeneratedAtBefore(
                ReportScope.WEEKLY, ReportStatus.PENDING, before)) {
            try {
                if (report.getWeeklyInput() == null) {
                    log.error("주간 보고서 복구 입력 누락. reportId={}", report.getId());
                    continue;
                }
                persistence.complete(report.getId(), fallback.generate(report.getWeeklyInput()),
                        LocalDateTime.now(ApiTimeZone.ZONE));
            } catch (RuntimeException exception) {
                log.error("주간 보고서 복구 실패. reportId={}", report.getId(), exception);
            }
        }
    }
}
