package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.global.config.ReportRecoveryMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 저장된 수집 실행에서 분석과 보고서 생성을 다시 수행하는 운영 복구 도구다.
 *
 * <p>수집 도중 프로세스가 종료되면 신규 기사와 finding은 저장됐어도 실행별 보고서가 없을 수 있다.
 * 복구 대상은 명시적으로 전달받고, 평상시 애플리케이션에서는 bean 자체를 만들지 않는다.
 * 복구 모드에서는 정기 작업과 기동 시 유실 실행 정리를 비활성화한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Conditional(ReportRecoveryMode.Enabled.class)
public class ReportRecoveryRunner implements ApplicationRunner {

    private final ReportCreationService reportCreationService;
    private final ArticleAnalysisPipeline analysisPipeline;
    private final CollectionRunArticleRepository observationRepository;
    private final FindingRepository findingRepository;

    @Value("${" + ReportRecoveryMode.RUN_IDS_PROPERTY + "}")
    String configuredRunIds;

    @Override
    public void run(ApplicationArguments args) {
        List<Long> runIds = parseRunIds(configuredRunIds);
        log.info("중단 실행 보고서 복구를 시작한다. runIds={}", runIds);

        for (Long runId : runIds) {
            Set<Long> observedArticleIds = new LinkedHashSet<>(
                    observationRepository.findArticleIdsByRunId(runId));
            analysisPipeline.recover(runId, observedArticleIds);
            int findingCount = findingRepository.findForReportByRunId(runId).size();
            Long reportId = reportCreationService.recover(runId);
            if (reportId == null) {
                throw new IllegalStateException(
                        "신규 기사 관측이 없어 보고서를 복구하지 못했습니다. runId=" + runId);
            }
            log.info("중단 실행 보고서를 복구했다. runId={} reportId={} findingCount={}",
                    runId, reportId, findingCount);
        }
    }

    static List<Long> parseRunIds(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("복구할 수집 실행 ID를 지정해야 합니다.");
        }
        LinkedHashSet<Long> runIds = new LinkedHashSet<>();
        Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .forEach(token -> {
                    try {
                        long runId = Long.parseLong(token);
                        if (runId < 1) {
                            throw new NumberFormatException();
                        }
                        runIds.add(runId);
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("수집 실행 ID가 올바르지 않습니다: " + token, exception);
                    }
                });
        return List.copyOf(runIds);
    }
}
