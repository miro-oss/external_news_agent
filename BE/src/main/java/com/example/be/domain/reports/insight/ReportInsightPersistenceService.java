package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportInsightPersistenceService {
    private final NewsReportInsightRepository repository;
    private final NewsReportRepository reports;
    private final ObjectMapper mapper;
    private final ReportInsightSnapshotAssembler assembler;

    @Transactional(readOnly = true)
    public List<NewsReportInsight> findCached(Long reportId, String hash, Collection<Audience> audiences) {
        return repository.findByReportIdAndInputHashAndPromptVersionAndRubricVersionAndAudienceIn(reportId, hash,
                ReportInsightService.PROMPT_VERSION, ReportInsightService.RUBRIC_VERSION, audiences);
    }

    @Transactional
    public List<NewsReportInsight> saveGenerated(ReportInsightSnapshotAssembler.Snapshot snapshot,
            AgentReportInsightResponse response) {
        var report = reports.findByIdForUpdate(snapshot.reportId())
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        if (report.getDeletedAt() != null || report.getReportStatus() == ReportStatus.PENDING)
            throw new ReportException(ReportErrorCode.REPORT_NOT_FOUND);
        if (!snapshot.inputHash().equals(assembler.assemble(snapshot.reportId()).inputHash()))
            throw new IllegalStateException("생성 중 리포트의 공개 가능한 근거가 변경되었습니다.");
        var meta = response.meta();
        var createdAt = LocalDateTime.now(ApiTimeZone.ZONE);
        List<NewsReportInsight> result = new ArrayList<>();
        for (var insight : response.insights()) {
            Audience audience = Audience.fromApiValue(insight.audience());
            var existing = findCached(snapshot.reportId(), snapshot.inputHash(), List.of(audience));
            if (!existing.isEmpty()) { result.add(existing.getFirst()); continue; }
            var payload = toPayload(insight, snapshot, meta.provider(), meta.model(), createdAt);
            result.add(repository.saveAndFlush(NewsReportInsight.builder().reportId(snapshot.reportId())
                    .audience(audience).inputHash(snapshot.inputHash()).promptVersion(ReportInsightService.PROMPT_VERSION)
                    .rubricVersion(ReportInsightService.RUBRIC_VERSION).payloadJson(mapper.writeValueAsString(payload))
                    .inputFindingCount(snapshot.findings().size()).llmProvider(meta.provider()).llmModel(meta.model())
                    .inputTokens(meta.inputTokens()).outputTokens(meta.outputTokens()).costUsd(meta.costUsd())
                    .credits(meta.credits()).createdAt(createdAt).build()));
        }
        return List.copyOf(result);
    }

    public ReportInsightDTO.AudienceInsight toDto(NewsReportInsight stored) {
        return mapper.readValue(stored.getPayloadJson(), ReportInsightDTO.AudienceInsight.class);
    }

    private ReportInsightDTO.AudienceInsight toPayload(AgentReportInsightResponse.Insight insight,
            ReportInsightSnapshotAssembler.Snapshot snapshot, String provider, String model, LocalDateTime createdAt) {
        Map<Long, Integer> inputOrder = new HashMap<>();
        for (int i = 0; i < snapshot.findings().size(); i++) inputOrder.put(snapshot.findings().get(i).id(), i);
        var sorted = insight.assessments().stream().sorted(Comparator
                .comparingInt((AgentReportInsightResponse.Assessment assessment) -> ReportImportance.priority(grade(assessment)))
                .reversed().thenComparing(Comparator.comparingDouble((AgentReportInsightResponse.Assessment assessment) ->
                        ReportImportance.score(assessment.axes().directness(), assessment.axes().impact(),
                                assessment.axes().urgency())).reversed())
                .thenComparingInt(assessment -> inputOrder.get(assessment.findingId()))).toList();
        List<ReportInsightDTO.Issue> issues = new ArrayList<>();
        for (int index = 0; index < Math.min(5, sorted.size()); index++) {
            var assessment = sorted.get(index);
            issues.add(new ReportInsightDTO.Issue(assessment.findingId(), grade(assessment), assessment.reason(),
                    List.copyOf(assessment.basisClaimIds()), assessment.axes(), index + 1));
        }
        // All facts come from saved grounded claims, including forecast/opinion attribution.
        var facts = snapshot.findings().stream().flatMap(finding -> finding.claims().stream().map(claim ->
                new ReportInsightDTO.Fact(claim.id(), claim.text(), claim.claimType(), claim.attributedTo(),
                        finding.id(), finding.articleId(), claim.evidenceSentenceIds(), "grounded"))).toList();
        return new ReportInsightDTO.AudienceInsight(Audience.fromApiValue(insight.audience()), insight.headline(),
                sorted.isEmpty() ? "unavailable" : grade(sorted.getFirst()), List.copyOf(insight.overview()),
                List.copyOf(issues), facts, List.copyOf(insight.implications()), List.copyOf(insight.watchItems()),
                provider, model, createdAt.atZone(ApiTimeZone.ZONE).toOffsetDateTime());
    }

    private String grade(AgentReportInsightResponse.Assessment assessment) {
        var axes = assessment.axes();
        return ReportImportance.grade(axes.directness(), axes.impact(), axes.urgency());
    }
}
