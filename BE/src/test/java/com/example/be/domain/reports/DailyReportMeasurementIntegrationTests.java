package com.example.be.domain.reports;

import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.DailyReportCreationService;
import com.example.be.domain.reports.service.ReportQueryService;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** 명시적으로 실행할 때만 실제 일일 보고서 1건을 생성하고 provider 사용량을 비교한다. */
@SpringBootTest(properties = {"news.agent.enabled=true", "news.reports.daily.enabled=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.reports.daily.measure", matches = "true")
class DailyReportMeasurementIntegrationTests {
    @Autowired private DailyReportCreationService creationService;
    @Autowired private NewsReportRepository reportRepository;
    @Autowired private ReportQueryService queryService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void measuresActualDailyInputTokensAgainstRunReports() throws Exception {
        String requested = System.getProperty("news.reports.daily.measure-date", "");
        LocalDate today = LocalDate.now(ApiTimeZone.ZONE);
        LocalDate date = requested.isBlank() ? jdbcTemplate.queryForObject("""
                SELECT TRUNC(run.started_at) AS report_date
                FROM news_collection_runs run LEFT JOIN news_reports report ON report.run_id = run.id
                WHERE run.started_at >= ? AND run.started_at < ?
                GROUP BY TRUNC(run.started_at)
                HAVING SUM(CASE WHEN run.status IN ('PENDING', 'RUNNING') THEN 1 ELSE 0 END) = 0
                   AND SUM(CASE WHEN report.report_status = 'GENERATED' THEN 1 ELSE 0 END) > 0
                ORDER BY report_date DESC FETCH FIRST 1 ROW ONLY
                """, (rs, row) -> rs.getDate("report_date").toLocalDate(),
                Timestamp.valueOf(today.minusDays(7).atStartOfDay()), Timestamp.valueOf(today.atStartOfDay()))
                : LocalDate.parse(requested);
        var baseline = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS report_count, COALESCE(SUM(report.input_tokens), 0) AS input_tokens
                FROM news_reports report JOIN news_collection_runs run ON run.id = report.run_id
                WHERE report.report_scope = 'RUN' AND run.started_at >= ? AND run.started_at < ?
                  AND report.report_status = 'GENERATED'
                """, Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
        long baselineTokens = ((Number) baseline.get("INPUT_TOKENS")).longValue();
        assertTrue(baselineTokens > 0, "실제 provider 토큰이 기록된 실행별 보고서가 필요합니다.");
        Long id = creationService.generate(date);
        assertNotNull(id, "해당 날짜의 실행이 모두 종료되어 있어야 합니다.");
        var report = reportRepository.findById(id).orElseThrow();
        var detail = queryService.getReport(id, true);
        var measurement = new LinkedHashMap<String, Object>();
        measurement.put("date", date.toString());
        measurement.put("reportId", id);
        measurement.put("status", report.getReportStatus());
        measurement.put("sourceRunCount", report.getSourceRunIds().size());
        measurement.put("runReportCount", baseline.get("REPORT_COUNT"));
        measurement.put("runReportInputTokens", baselineTokens);
        measurement.put("dailyInputTokens", report.getInputTokens());
        measurement.put("selectedIssueCount", detail.getSummaryStats().getFindingCount());
        measurement.put("model", report.getModelName());
        measurement.put("credits", report.getCredits());
        measurement.put("costUsd", report.getCostUsd());
        Path output = Path.of("build/reports/daily-report-measurement.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(measurement));
        assertEquals(ReportStatus.GENERATED, report.getReportStatus(), "실제 provider 생성이 완료되어야 합니다.");
        assertNotNull(report.getInputTokens());
        assertTrue(report.getInputTokens() > 0 && report.getInputTokens() < baselineTokens);
        assertTrue(detail.getSummaryStats().getFindingCount() > 0);
        assertTrue(detail.getSummaryStats().getFindingCount() <= 10);
        assertTrue(detail.getFindings().stream().allMatch(f -> f.getRunId() != null));
        assertEquals(id, creationService.generate(date));
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_runs WHERE agent_task = 'REPORT'
                    AND target_type = 'REPORT' AND target_id = ? AND collection_run_id IS NULL
                """, Long.class, id));
    }
}
