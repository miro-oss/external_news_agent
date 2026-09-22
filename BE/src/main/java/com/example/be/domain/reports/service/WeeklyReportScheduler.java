package com.example.be.domain.reports.service;

import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReportScheduler {
    private final WeeklyReportCreationService creation;
    @Value("${news.reports.weekly.enabled:true}") private boolean enabled = true;
    @Value("${news.scheduling.enabled:true}") private boolean schedulingEnabled = true;
    @Value("${news.reports.weekly.backfill-weeks:4}") private int backfillWeeks = 4;

    @Scheduled(fixedDelayString = "${news.reports.weekly.poll-interval-ms:300000}")
    public void generateDueReports() {
        if (!enabled || !schedulingEnabled) return;
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        creation.recoverInterrupted(now.minusMinutes(30));
        LocalDate currentMonday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // Read only saved daily reports; bound startup work even if misconfigured.
        for (int weeks = 1; weeks <= Math.clamp(backfillWeeks, 1, 12); weeks++) {
            LocalDate monday = currentMonday.minusWeeks(weeks);
            try {
                creation.generate(monday);
            } catch (RuntimeException exception) {
                log.error("주간 보고서 생성 실패. monday={}", monday, exception);
            }
        }
    }
}
