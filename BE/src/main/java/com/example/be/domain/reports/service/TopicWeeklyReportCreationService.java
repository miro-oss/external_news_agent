package com.example.be.domain.reports.service;

import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import java.util.List;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TopicWeeklyReportCreationService {
    private final TopicWeeklyReportPersistenceService reservations;
    private final AgentWeeklyReportOrchestrator orchestrator;
    private final WeeklyReportGenerator fallback;
    private final ReportPersistenceService persistence;
    private final TopicWeeklyReportSelector selector;

    public ReportResDTO.WeeklyCreated generate(Long topicId, LocalDate monday) {
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        var reservation = reservations.reserve(topicId, monday, now);
        if (!reservation.owner()) return new ReportResDTO.WeeklyCreated(reservation.reportId(), false, reservation.ready());
        var input = reservation.input();
        if (!reservation.prepared().isEmpty()) {
            var selection = selector.selectWeek(reservation.prepared());
            // Keep originally available analysis dates even when every candidate was deliberately excluded.
            var availableDates = reservation.prepared().stream().filter(day -> !day.candidates().isEmpty())
                    .map(TopicWeeklyReportSelector.Prepared::date).toList();
            var sources = selection.selections().stream().filter(day -> availableDates.contains(day.source().reportDate()))
                    .map(TopicWeeklyReportSelector.Selection::source).toList();
            var sourceDates = sources.stream().map(WeeklyReportInput.DailySource::reportDate).toList();
            List<LocalDate> missing = monday.datesUntil(monday.plusWeeks(1)).filter(date -> !sourceDates.contains(date)).toList();
            input = new WeeklyReportInput(monday, monday.plusDays(6), sources, missing, topicId, input.topicName());
            persistence.freezeCompanySelection(reservation.reportId(), input.sourceFindingIds(),
                    selection.companySuppressedFindingIds(), input, selection.applications(), java.util.Set.of(),
                    selection.comparisonScopeKnown(), now);
        }
        ReportDocument document;
        try {
            document = orchestrator.generate(reservation.reportId(), input, now);
        } catch (RuntimeException exception) {
            log.warn("주제별 주간 보고서 생성 실패로 저장 근거를 사용합니다. reportId={}", reservation.reportId(), exception);
            document = fallback.generate(input);
        }
        persistence.complete(reservation.reportId(), document, LocalDateTime.now(ApiTimeZone.ZONE));
        return new ReportResDTO.WeeklyCreated(reservation.reportId(), true, true);
    }
}
