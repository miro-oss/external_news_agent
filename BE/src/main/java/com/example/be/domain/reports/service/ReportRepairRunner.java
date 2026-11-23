package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.service.FailedAnalysisRecoveryService;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.global.config.ReportRecoveryMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Explicit, bounded maintenance of one saved RUN report. Dry-run is the default. */
@Slf4j
@Component
@RequiredArgsConstructor
@Conditional(ReportRecoveryMode.RepairEnabled.class)
public class ReportRepairRunner implements ApplicationRunner {
    private final ReportMaintenanceService reports;
    private final FailedAnalysisRecoveryService analyses;
    private final ConfigurableApplicationContext context;

    @Value("${" + ReportRecoveryMode.REPAIR_REPORT_ID_PROPERTY + "}") String reportId;
    @Value("${news.reports.repair.apply:false}") boolean apply;
    @Value("${news.reports.repair.recover-analyses:false}") boolean recoverAnalyses;
    @Value("${news.reports.repair.regenerate-report:false}") boolean regenerateReport;
    @Value("${news.reports.repair.recovery-id:}") String recoveryId;
    @Value("${news.reports.repair.max-articles:20}") int maxArticles;
    @Value("${news.reports.repair.promote-event-title:}") String promoteEventTitle;
    @Value("${news.reports.repair.briefing-position:3}") int briefingPosition;

    @Override
    public void run(ApplicationArguments args) {
        try {
            validate();
            var snapshot = reports.inspect(parseReportId(reportId));
            if (snapshot.runId() == null) throw new IllegalArgumentException("실행별 보고서만 복구할 수 있습니다.");
            if (hasPromotion() && !snapshot.eventTitles().contains(promoteEventTitle)) {
                throw new IllegalArgumentException("지정한 중요 이벤트 제목이 저장된 보고서에 없습니다.");
            }
            log.info("보고서 복구 계획 apply={} reportId={} runId={} status={} fingerprint={} events={}",
                    apply, snapshot.reportId(), snapshot.runId(), snapshot.status(), snapshot.fingerprint(),
                    snapshot.eventTitles());
            if (recoverAnalyses) {
                var plan = analyses.plan(snapshot.runId(), maxArticles);
                log.info("실패 분석 복구 계획 {}", plan);
            }
            if (!apply) {
                log.info("확인만 완료했습니다. 보고서 변경 및 AI 호출은 실행하지 않았습니다.");
                return;
            }

            // Save the requested edit even when provider recovery later fails.
            if (hasPromotion()) {
                snapshot = reports.promote(snapshot.reportId(), snapshot.fingerprint(),
                        promoteEventTitle, briefingPosition - 1);
                log.info("저장 이벤트 브리핑 순서를 반영했습니다. reportId={}", snapshot.reportId());
            }
            if (recoverAnalyses) {
                var result = analyses.recover(snapshot.runId(), recoveryId, maxArticles);
                log.info("실패 분석 복구 결과 {}", result);
                // Never regenerate a report while a failed attempt remains unresolved.
                var remaining = analyses.plan(snapshot.runId(), maxArticles);
                if (!result.failedArticleIds().isEmpty() || !remaining.articleIds().isEmpty()
                        || remaining.skippedUnavailable() > 0) {
                    throw new IllegalStateException("미복구 분석이 남아 보고서 재생성을 중단했습니다. 기존 본문과 브리핑 편집은 보존됩니다.");
                }
            }
            if (regenerateReport && snapshot.status() == ReportStatus.FALLBACK) {
                var result = hasPromotion()
                        ? reports.regenerateFallback(snapshot.reportId(), snapshot.fingerprint(), recoveryId,
                                promoteEventTitle, briefingPosition - 1)
                        : reports.regenerateFallback(snapshot.reportId(), snapshot.fingerprint(), recoveryId);
                if (!result.replaced()) {
                    throw new IllegalStateException("AI 보고서 생성이 완료되지 않아 기존 본문을 보존했습니다.");
                }
                log.info("대체 보고서를 AI 보고서로 복구했습니다. reportId={}", snapshot.reportId());
            } else if (regenerateReport) {
                log.info("대체 보고서가 아니므로 본문을 재생성하지 않았습니다. reportId={}", snapshot.reportId());
            }
            log.info("보고서 복구 완료. reportId={} 자동 발송은 실행하지 않았습니다.", snapshot.reportId());
        } finally {
            context.close();
        }
    }

    private void validate() {
        var environment = context.getEnvironment();
        if (environment.containsProperty(ReportRecoveryMode.RUN_IDS_PROPERTY)
                || environment.containsProperty(ReportRecoveryMode.SNAPSHOT_REPORT_IDS_PROPERTY)) {
            throw new IllegalArgumentException("보고서 복구 모드를 함께 실행할 수 없습니다.");
        }
        if (briefingPosition < 1 || briefingPosition > 3) {
            throw new IllegalArgumentException("핵심 브리핑 위치는 1~3이어야 합니다.");
        }
        if (maxArticles < 1 || maxArticles > 50) {
            throw new IllegalArgumentException("복구 기사 상한은 1~50이어야 합니다.");
        }
        if (!recoverAnalyses && !regenerateReport && !hasPromotion()) {
            throw new IllegalArgumentException("실패 분석 복구, 보고서 재생성 또는 브리핑 편집을 지정해야 합니다.");
        }
        if ((recoverAnalyses || regenerateReport) && (recoveryId == null
                || !recoveryId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) {
            throw new IllegalArgumentException("복구 ID는 영문·숫자로 시작하는 1~64자의 영문·숫자·밑줄·하이픈이어야 합니다.");
        }
    }

    private boolean hasPromotion() {
        return promoteEventTitle != null && !promoteEventTitle.isBlank();
    }

    static long parseReportId(String value) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // A report target is mandatory even in dry-run mode.
        }
        throw new IllegalArgumentException("복구할 보고서 ID는 양수여야 합니다.");
    }
}
