package com.example.be.domain.reports.service;

import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.config.ReportRecoveryMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@Conditional(ReportRecoveryMode.SnapshotEnabled.class)
public class ReportSnapshotRecoveryRunner implements ApplicationRunner {
    private final ReportSnapshotRecoveryService recovery;
    private final ReportSnapshotRecoveryComparison comparisons;
    private final ConfigurableApplicationContext context;
    @Value("${" + ReportRecoveryMode.SNAPSHOT_REPORT_IDS_PROPERTY + "}") String reportIds;
    @Value("${news.reports.snapshot-recovery.apply:false}") boolean apply;

    @Override public void run(ApplicationArguments args) {
        try {
            if (context.getEnvironment().containsProperty(ReportRecoveryMode.RUN_IDS_PROPERTY))
                throw new IllegalArgumentException("수집 실행 복구와 저장 자료 복원을 함께 실행할 수 없습니다.");
            var ids = ReportRecoveryRunner.parseRunIds(reportIds);
            var results = recovery.recover(ids, apply, LocalDateTime.now(ApiTimeZone.ZONE));
            results.forEach(result -> log.info("보고서 저장 자료 복원 apply={} result={}", apply, result));
            if (apply) results.stream().filter(result -> !result.hiddenDependency())
                    .forEach(result -> log.info("복원 보고서 비교 reportId={} status={}",
                            result.reportId(), comparisons.complete(result.reportId())));
        } finally { context.close(); }
    }
}
