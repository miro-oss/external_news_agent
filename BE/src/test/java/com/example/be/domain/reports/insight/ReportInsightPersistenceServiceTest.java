package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightRequest;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import static com.example.be.domain.reports.insight.ReportInsightTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportInsightPersistenceServiceTest {
    NewsReportInsightRepository repository = mock(NewsReportInsightRepository.class);
    NewsReportRepository reports = mock(NewsReportRepository.class);
    ReportInsightSnapshotAssembler assembler = mock(ReportInsightSnapshotAssembler.class);
    ReportInsightPersistenceService service = new ReportInsightPersistenceService(repository, reports, new ObjectMapper(), assembler);
    @BeforeEach void setup() {
        when(reports.findByIdForUpdate(10L)).thenReturn(Optional.of(NewsReport.builder().id(10L).reportStatus(ReportStatus.GENERATED).build()));
        when(assembler.assemble(10L)).thenReturn(snapshot("a".repeat(64)));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }
    @Test void reconstructsFactsAndSortsScoresIndependentlyOfAssessmentOrder() {
        var snapshot = snapshot("a".repeat(64));
        var source = snapshot.findings().getFirst();
        var opinion = new AgentReportInsightRequest.ClaimPayload("50:0", "분석가는 공급에 우려를 표했다.", "OPINION", "분석가", List.of(0));
        var changed = new AgentReportInsightRequest.FindingPayload(source.id(), source.articleId(), source.articleTitle(),
                source.canonicalUrl(), source.publishedAt(), source.topicName(), List.of(opinion), source.sentences());
        var original = new ReportInsightSnapshotAssembler.Snapshot(snapshot.reportId(), snapshot.runId(), snapshot.inputHash(),
                snapshot.report(), List.of(changed, snapshot.findings().getLast()));
        var stored = service.saveGenerated(original, response()).getFirst();
        var dto = service.toDto(stored);
        assertEquals("high", dto.importance());
        assertEquals(List.of(40L, 50L), dto.issues().stream().map(ReportInsightDTO.Issue::findingId).toList());
        assertEquals(List.of(1, 2), dto.issues().stream().map(ReportInsightDTO.Issue::rank).toList());
        assertEquals(opinion.text(), dto.facts().getFirst().text());
        assertEquals("OPINION", dto.facts().getFirst().claimType());
        assertEquals("분석가", dto.facts().getFirst().attributedTo());
        assertEquals(List.of(0), dto.facts().getFirst().evidenceSentenceIds());
        assertEquals(ReportInsightService.RUBRIC_VERSION, stored.getRubricVersion());
    }
    @Test void equalScoreUsesCapturedInputOrderNotModelOrderOrId() {
        var dto = service.toDto(service.saveGenerated(snapshot("a".repeat(64)),
                response(List.of(assessment(40, 3, 3, 3), assessment(50, 3, 3, 3)), java.math.BigDecimal.ONE)).getFirst());
        assertEquals(List.of(50L, 40L), dto.issues().stream().map(ReportInsightDTO.Issue::findingId).toList());
    }
    @Test void reportLockAndExactCacheAvoidDuplicateInsert() {
        var existing = NewsReportInsight.builder().audience(Audience.CHIP_MAKER).build();
        when(repository.findByReportIdAndInputHashAndPromptVersionAndRubricVersionAndAudienceIn(
                eq(10L), anyString(), anyString(), anyString(), anyCollection())).thenReturn(List.of(existing));
        assertSame(existing, service.saveGenerated(snapshot("a".repeat(64)), response()).getFirst());
        verify(reports).findByIdForUpdate(10L);
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void sourceChangedDuringProviderCallCannotBeSavedOrReturned() {
        when(assembler.assemble(10L)).thenReturn(snapshot("b".repeat(64)));
        assertThrows(IllegalStateException.class, () -> service.saveGenerated(snapshot("a".repeat(64)), response()));
        verify(repository, never()).saveAndFlush(any());
    }
}
