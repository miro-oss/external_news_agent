package com.example.be.domain.reports.service;

import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportScheduler {

    private final DailyReportJdbcRepository repository;
    private final DailyReportCreationService creationService;

    @Value("${news.reports.daily.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${news.reports.daily.poll-interval-ms:300000}")
    public void generateDueReports() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        creationService.recoverInterrupted(now.minusMinutes(30));
        // 기동하지 못한 날도 복구하되 오래된 전체 이력을 한꺼번에 유료 호출하지 않는다.
        for (var date : repository.findDueDates(now.toLocalDate().minusDays(7), now.toLocalDate())) {
            try {
                creationService.generate(date);
            } catch (RuntimeException exception) {
                log.error("일일 보고서 생성 실패. date={}", date, exception);
            }
        }
    }
}
