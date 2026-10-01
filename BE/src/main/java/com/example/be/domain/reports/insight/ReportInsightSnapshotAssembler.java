package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightRequest;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
@RequiredArgsConstructor
public class ReportInsightSnapshotAssembler {
    public static final int MAX_FINDINGS = 50;
    private final NewsReportRepository reports;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevancePolicy;
    private final ObjectMapper mapper;
    private final ReportEventFeedbackProjection feedbackProjection;

    @Transactional(readOnly = true)
    public Snapshot assemble(Long reportId) {
        return assemble(reportId, true);
    }

    @Transactional(readOnly = true)
    public Snapshot assembleForRead(Long reportId) {
        return assemble(reportId, false);
    }

    private Snapshot assemble(Long reportId, boolean validateGeneration) {
        NewsReport report = reports.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        var reportPayload = new AgentReportInsightRequest.ReportPayload(report.getId(), report.getTitle(),
                report.getReportScope().name(), report.getReportDate(), report.getReportEndDate());
        var original = ReportFindings.loadVisible(report, findings, relevancePolicy);
        // Match revision-bound feedback before narrowing the original report evidence for insights.
        List<Finding> visible = feedbackProjection.project(report, original,
                feedbackProjection.reviews(report.getId())).findings();
        if (report.getReportScope() == ReportScope.RUN && report.isCoverageRecorded()) {
            Map<Long, Finding> byId = new HashMap<>();
            visible.forEach(finding -> byId.put(finding.getId(), finding));
            visible = report.getReflectedFindingIds().stream().filter(byId::containsKey).map(byId::get).toList();
        }
        var selected = visible.stream()
                .filter(finding -> reflectedInReport(report, finding))
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .filter(finding -> finding.getArticle().getTopic() != null
                        && StringUtils.hasText(finding.getArticle().getTopic().getName()))
                .map(this::payload).filter(finding -> !finding.claims().isEmpty()).toList();
        if (validateGeneration && selected.isEmpty()) throw new GeneralException(GeneralErrorCode.CONFLICT,
                "이 리포트는 인사이트에 사용할 검증된 근거가 없습니다.");
        if (validateGeneration && selected.size() > MAX_FINDINGS) throw new GeneralException(GeneralErrorCode.CONFLICT,
                "리포트 관점 인사이트는 검증된 근거 50개까지 지원합니다.");
        return new Snapshot(report.getId(), report.getRunId(), hash(new Fingerprint(reportPayload, selected)),
                reportPayload, selected);
    }

    private boolean reflectedInReport(NewsReport report, Finding finding) {
        if (report.getReportScope() != ReportScope.RUN) return true;
        if (report.isCoverageRecorded()) return report.getReflectedFindingIds().contains(finding.getId());
        // Legacy RUN rows lack an explicit selection. Never append findings produced later.
        return report.getGeneratedAt() != null && finding.getAnalyzedAt() != null
                && !finding.getAnalyzedAt().isAfter(report.getGeneratedAt());
    }

    private AgentReportInsightRequest.FindingPayload payload(Finding finding) {
        Map<Integer, String> sentenceTexts = new TreeMap<>();
        Set<Integer> ambiguous = new HashSet<>();
        if (finding.getSections() != null) for (var section : finding.getSections()) {
            if (section != null && section.index() >= 0 && StringUtils.hasText(section.text())) {
                if (sentenceTexts.putIfAbsent(section.index(), section.text()) != null) ambiguous.add(section.index());
            }
        }
        ambiguous.forEach(sentenceTexts::remove);
        List<AgentReportInsightRequest.ClaimPayload> claims = new ArrayList<>();
        var points = finding.getEffectiveKeyPoints();
        for (int index = 0; index < points.size(); index++) {
            FindingKeyPoint point = points.get(index);
            if (point == null || !"grounded".equals(point.groundedness()) || !StringUtils.hasText(point.text())
                    || !Set.of("FACT", "FORECAST", "OPINION").contains(point.claimType())
                    || ("OPINION".equals(point.claimType())
                        ? !StringUtils.hasText(point.attributedTo()) || point.attributedTo().length() > 200
                        : point.attributedTo() != null)
                    || point.evidence().isEmpty() || !sentenceTexts.keySet().containsAll(point.evidence())
                    || point.evidence().stream().distinct().count() != point.evidence().size()) continue;
            claims.add(new AgentReportInsightRequest.ClaimPayload(finding.getId() + ":" + index,
                    point.text(), point.claimType(), point.attributedTo(), point.evidence()));
        }
        Set<Integer> used = new HashSet<>();
        claims.forEach(claim -> used.addAll(claim.evidenceSentenceIds()));
        var sentences = sentenceTexts.entrySet().stream().filter(entry -> used.contains(entry.getKey()))
                .map(entry -> new AgentReportInsightRequest.SentencePayload(entry.getKey(), entry.getValue())).toList();
        var article = finding.getArticle();
        return new AgentReportInsightRequest.FindingPayload(finding.getId(), article.getId(), article.getTitle(),
                article.getCanonicalUrl(), article.getPublishedAt() == null ? null : article.getPublishedAt()
                .atZoneSameInstant(ApiTimeZone.ZONE).toLocalDate().toString(),
                article.getTopic() == null ? "" : article.getTopic().getName(), List.copyOf(claims), sentences);
    }

    private String hash(Object input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(input))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private record Fingerprint(AgentReportInsightRequest.ReportPayload report,
            List<AgentReportInsightRequest.FindingPayload> findings) { }
    public record Snapshot(Long reportId, Long runId, String inputHash,
            AgentReportInsightRequest.ReportPayload report, List<AgentReportInsightRequest.FindingPayload> findings) { }
}
