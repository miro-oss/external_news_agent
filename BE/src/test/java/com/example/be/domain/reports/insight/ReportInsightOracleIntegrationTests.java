package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "news.agent.enabled=false") @ActiveProfiles("local") @Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportInsightOracleIntegrationTests {
    @Autowired NewsReportRepository reports;
    @Autowired NewsReportInsightRepository repository;
    @Autowired ReportInsightPersistenceService persistence;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;

    @Test void clobPayloadPreservesAttributionEvidenceAndCacheKeyIsUniqueAcrossVersions() {
        var now = LocalDateTime.now();
        var report = reports.saveAndFlush(NewsReport.builder().title("관점 리포트 통합 테스트")
                .reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2200, 1, 1).plusDays(new Random().nextInt(100000)))
                .markdownBody("본문").modelName("mock").reportStatus(ReportStatus.MOCK).generatedAt(now).build());
        String verbatim = "발언자가 전망을 설명했다. ".repeat(500);
        var payload = new ReportInsightDTO.AudienceInsight(Audience.CHIP_MAKER, "관점 판단", "unavailable", List.of(), List.of(),
                List.of(new ReportInsightDTO.Fact("50:0", verbatim, "OPINION", "발언자", 50L, 150L, List.of(0), "grounded")),
                List.of(), List.of(), "mock", "mock", now.atOffset(ZoneOffset.ofHours(9)));
        String json = mapper.writeValueAsString(payload);
        assertTrue(json.length() > 4000);
        var stored = repository.saveAndFlush(row(report.getId(), json, now, "report-importance.v1"));
        entityManager.clear();
        var loaded = repository.findByReportIdAndInputHashAndPromptVersionAndRubricVersionAndAudienceIn(report.getId(),
                "a".repeat(64), ReportInsightService.PROMPT_VERSION, "report-importance.v1", List.of(Audience.CHIP_MAKER));
        assertEquals(stored.getId(), loaded.getFirst().getId());
        var result = persistence.toDto(loaded.getFirst());
        assertEquals(verbatim, result.facts().getFirst().text());
        assertEquals("OPINION", result.facts().getFirst().claimType());
        assertEquals("발언자", result.facts().getFirst().attributedTo());
        assertEquals(List.of(0), result.facts().getFirst().evidenceSentenceIds());
        repository.saveAndFlush(row(report.getId(), json, now, "report-importance.v2"));
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(row(report.getId(), json, now, "report-importance.v1")));
    }
    private NewsReportInsight row(Long reportId, String json, LocalDateTime created, String rubric) {
        return NewsReportInsight.builder().reportId(reportId).audience(Audience.CHIP_MAKER).inputHash("a".repeat(64))
                .promptVersion(ReportInsightService.PROMPT_VERSION).rubricVersion(rubric).payloadJson(json)
                .inputFindingCount(1).llmProvider("mock").llmModel("mock").createdAt(created).build();
    }
}
