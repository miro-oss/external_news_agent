package com.example.be.domain.reports.service;

import com.example.be.domain.reports.dto.res.ReportResDTO;
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

    public ReportResDTO.WeeklyCreated generate(Long topicId, LocalDate monday) {
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        var reservation = reservations.reserve(topicId, monday, now);
        if (!reservation.owner()) return new ReportResDTO.WeeklyCreated(reservation.reportId(), false, reservation.ready());
        ReportDocument document;
        try {
            document = orchestrator.generate(reservation.reportId(), reservation.input(), now);
        } catch (RuntimeException exception) {
            log.warn("주제별 주간 보고서 생성 실패로 저장 근거를 사용합니다. reportId={}", reservation.reportId(), exception);
            document = fallback.generate(reservation.input());
        }
        persistence.complete(reservation.reportId(), document, LocalDateTime.now(ApiTimeZone.ZONE));
        return new ReportResDTO.WeeklyCreated(reservation.reportId(), true, true);
    }
}
