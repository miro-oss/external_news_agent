package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.dto.res.SensitivityResDTO;
import com.example.be.domain.analysis.agent.investigation.InvestigationTrace;
import com.example.be.domain.analysis.agent.investigation.IssueInvestigationJdbcRepository;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingCategory;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.analysis.service.SensitivityCalculator;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.repository.DeliveryLogRepository;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.repository.NewsReportSpecification;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryServiceImpl implements ReportQueryService {

    private static final String DELIVERY_STATUS_NOT_SENT = "NOT_SENT";

    private final NewsReportRepository reportRepository;
    private final FindingRepository findingRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final NewsIssueRepository newsIssueRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final SensitivityCalculator sensitivityCalculator;
    private final IssueInvestigationJdbcRepository investigationRepository;

    @Override
    public PageResponse<ReportResDTO.Summary> getReports(String from, String to, int page, int size) {
        validatePage(page, size);
        LocalDateTime parsedFrom = parseDateTime(from, false);
        LocalDateTime parsedTo = parseDateTime(to, true);
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            throw badRequest("from은 to보다 이후일 수 없습니다.");
        }

        Page<NewsReport> reports = reportRepository.findAll(
                NewsReportSpecification.generatedBetween(parsedFrom, parsedTo)
                        .and((root, query, criteriaBuilder) ->
                                criteriaBuilder.notEqual(root.get("reportStatus"), ReportStatus.PENDING)),
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("generatedAt"), Sort.Order.desc("id"))));
        Map<Long, FindingRepository.ReportCount> counts = countsByRun(reports.getContent());
        Map<Long, String> deliveryStatuses = deliveryStatuses(reports.getContent());
        List<ReportResDTO.Summary> content = reports.getContent().stream()
                .map(report -> toSummary(report, counts.get(report.getRun().getId()),
                        deliveryStatuses.getOrDefault(report.getId(), DELIVERY_STATUS_NOT_SENT)))
                .toList();
        return PageResponse.of(content, page, size, reports.getTotalElements());
    }

    @Override
    public ReportResDTO.Detail getLatest(boolean includeFindings) {
        return reportRepository.findFirstByReportStatusNotOrderByGeneratedAtDescIdDesc(ReportStatus.PENDING)
                .map(report -> toDetail(report, includeFindings))
                .orElse(null);
    }

    @Override
    public ReportResDTO.Detail getReport(Long reportId, boolean includeFindings) {
        NewsReport report = reportRepository.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        return toDetail(report, includeFindings);
    }

    private ReportResDTO.Summary toSummary(NewsReport report,
                                           FindingRepository.ReportCount count,
                                           String deliveryStatus) {
        return ReportResDTO.Summary.builder()
                .id(report.getId())
                .runId(report.getRun().getId())
                .title(report.getTitle())
                .generatedAt(toOffset(report.getGeneratedAt()))
                .modelName(report.getModelName())
                .findingCount(count == null ? 0 : count.getFindingCount())
                .highSensitivityCount(count == null ? 0 : count.getHighSensitivityCount())
                .deliveryStatus(deliveryStatus)
                .build();
    }

    private Map<Long, String> deliveryStatuses(List<NewsReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> reportIds = reports.stream().map(NewsReport::getId).toList();
        Map<Long, String> statuses = new LinkedHashMap<>();
        for (DeliveryLogRepository.ReportDeliveryStatusCount count
                : deliveryLogRepository.countStatusesByReportIds(reportIds)) {
            statuses.putIfAbsent(count.getReportId(), "FAILED");
            if (count.getStatus() == DeliveryStatus.SENT) {
                statuses.put(count.getReportId(), "SENT");
            }
        }
        return statuses;
    }

    private ReportResDTO.Detail toDetail(NewsReport report, boolean includeFindings) {
        Long runId = report.getRun().getId();
        List<Finding> findings = includeFindings
                ? ReportFindingOrder.sort(findingRepository.findForReportByRunId(runId))
                : null;
        Map<Long, Long> issueIdsByArticle = includeFindings
                ? issueIdsByArticle(findings)
                : Map.of();
        Map<Long, NewsIssue> issuesById = includeFindings
                ? issuesById(issueIdsByArticle.values())
                : Map.of();
        Map<Long, InvestigationTrace> investigationsByIssue = includeFindings
                ? investigationsByIssue(runId)
                : Map.of();
        ReportResDTO.SummaryStats summaryStats = includeFindings
                ? toStats(findings)
                : toStatsFromCounts(findingRepository.countStatsByRunId(
                        runId,
                        sensitivityCalculator.mediumThreshold(),
                        sensitivityCalculator.highThreshold()));
        return ReportResDTO.Detail.builder()
                .id(report.getId())
                .runId(runId)
                .title(report.getTitle())
                .markdownBody(report.getMarkdownBody())
                .modelName(report.getModelName())
                .promptVersion(report.getPromptVersion())
                .llmProvider(report.getLlmProvider())
                .generatedAt(toOffset(report.getGeneratedAt()))
                .summaryStats(summaryStats)
                .findings(includeFindings ? findings.stream()
                        .map(finding -> {
                            Long issueId = issueIdsByArticle.get(finding.getArticle().getId());
                            NewsIssue issue = issueId == null ? null : issuesById.get(issueId);
                            return toFinding(
                                    finding, issueId, issue,
                                    issueId == null ? null : investigationsByIssue.get(issueId));
                        })
                        .toList() : null)
                .build();
    }

    private Map<Long, Long> issueIdsByArticle(List<Finding> findings) {
        if (findings.isEmpty()) {
            return Map.of();
        }
        Set<Long> articleIds = findings.stream()
                .map(finding -> finding.getArticle().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Long> topicIdsByArticle = findings.stream()
                .collect(Collectors.toMap(
                        finding -> finding.getArticle().getId(),
                        finding -> finding.getArticle().getTopic().getId(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Map<Long, Long> result = new LinkedHashMap<>();
        OracleInClause.batches(articleIds).stream()
                .flatMap(ids -> issueArticleRepository.findCoverageMembershipsByArticleIds(ids).stream())
                .filter(membership -> membership.getTopicId()
                        .equals(topicIdsByArticle.get(membership.getArticleId())))
                .forEach(membership -> result.putIfAbsent(
                        membership.getArticleId(), membership.getIssueId()));
        return result;
    }

    private Map<Long, NewsIssue> issuesById(Collection<Long> issueIds) {
        Set<Long> uniqueIssueIds = new LinkedHashSet<>(issueIds);
        if (uniqueIssueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, NewsIssue> result = new LinkedHashMap<>();
        OracleInClause.batches(uniqueIssueIds).stream()
                .flatMap(ids -> newsIssueRepository.findAllById(ids).stream())
                .forEach(issue -> result.put(issue.getId(), issue));
        return result;
    }

    private ReportResDTO.SummaryStats toStats(List<Finding> findings) {
        Map<String, Long> bySensitivityLevel = orderedCounts(findings,
                finding -> sensitivityCalculator.level(finding.getSensitivity().getScore()).toApiValue(),
                List.of("high", "medium", "low"));
        Map<String, Long> byCategory = orderedCounts(findings, Finding::getCategory,
                FindingCategory.ORDERED_VALUES);
        return ReportResDTO.SummaryStats.builder()
                .findingCount(findings.size())
                .newCount(findings.stream().filter(finding -> finding.getChangeType() == ChangeType.NEW).count())
                .updatedCount(findings.stream().filter(finding -> finding.getChangeType() == ChangeType.UPDATED).count())
                .bySensitivityLevel(bySensitivityLevel)
                .byCategory(byCategory)
                .build();
    }

    private ReportResDTO.SummaryStats toStatsFromCounts(List<FindingRepository.ReportStatsCount> counts) {
        Map<String, Long> bySensitivityLevel = emptyOrderedCounts(List.of("high", "medium", "low"));
        Map<String, Long> byCategory = emptyOrderedCounts(FindingCategory.ORDERED_VALUES);
        long findingCount = 0;
        long newCount = 0;
        long updatedCount = 0;
        for (FindingRepository.ReportStatsCount count : counts) {
            long value = count.getFindingCount();
            findingCount += value;
            if (count.getChangeType() == ChangeType.NEW) {
                newCount += value;
            } else if (count.getChangeType() == ChangeType.UPDATED) {
                updatedCount += value;
            }
            bySensitivityLevel.merge("high", count.getHighSensitivityCount(), Long::sum);
            bySensitivityLevel.merge("medium", count.getMediumSensitivityCount(), Long::sum);
            bySensitivityLevel.merge("low", count.getLowSensitivityCount(), Long::sum);
            byCategory.merge(count.getCategory(), value, Long::sum);
        }
        return ReportResDTO.SummaryStats.builder()
                .findingCount(findingCount)
                .newCount(newCount)
                .updatedCount(updatedCount)
                .bySensitivityLevel(bySensitivityLevel)
                .byCategory(byCategory)
                .build();
    }

    private ReportResDTO.Finding toFinding(Finding finding,
                                           Long issueId,
                                           NewsIssue issue,
                                           InvestigationTrace investigation) {
        return ReportResDTO.Finding.builder()
                .id(finding.getId())
                .articleId(finding.getArticle().getId())
                .issueId(issueId)
                .issue(toIssueSummary(issue))
                .articleTitle(finding.getArticle().getTitle())
                .canonicalUrl(finding.getArticle().getCanonicalUrl())
                .changeType(finding.getChangeType().name())
                .summary(finding.getSummary())
                .keyPoints(FindingEvidencePolicy.supportedKeyPoints(finding).stream()
                        .map(point -> ReportResDTO.KeyPoint.builder()
                                .text(point.text())
                                .evidence(point.evidence())
                                .groundedness(point.groundedness())
                                .groundingReason(point.groundingReason())
                                .claimType(point.claimType())
                                .attributedTo(point.attributedTo())
                                .build())
                        .toList())
                .intent(finding.getIntent())
                .sentiment(finding.getSentiment().toApiValue())
                .sensitivity(SensitivityResDTO.of(finding.getSensitivity(),
                        sensitivityCalculator.level(finding.getSensitivity().getScore())))
                .relevance(finding.getRelevance().toApiValue())
                .category(finding.getCategory())
                .perspectiveTags(finding.getPerspectiveTags() == null ? List.of()
                        : finding.getPerspectiveTags().stream()
                        .map(tag -> ReportResDTO.PerspectiveTag.builder()
                                .audience(tag.audience().name())
                                .relevance(tag.relevance().toApiValue())
                                .hook(tag.hook())
                                .evidenceSentenceIds(tag.evidenceSentenceIds())
                                .build())
                        .toList())
                .investigation(toInvestigation(investigation))
                .build();
    }

    private Map<Long, InvestigationTrace> investigationsByIssue(Long runId) {
        Map<Long, InvestigationTrace> result = investigationRepository.findTraces(runId);
        return result == null ? Map.of() : result;
    }

    private ReportResDTO.Investigation toInvestigation(InvestigationTrace trace) {
        if (trace == null) {
            return null;
        }
        return ReportResDTO.Investigation.builder()
                .status(trace.status())
                .stepCount(trace.stepCount())
                .addedArticleCount(trace.addedArticleCount())
                .addedEvidenceCount(trace.addedEvidenceCount())
                .reason(trace.reason())
                .rejectionReason(trace.rejectionReason())
                .build();
    }

    private ReportResDTO.IssueSummary toIssueSummary(NewsIssue issue) {
        if (issue == null) {
            return null;
        }
        return ReportResDTO.IssueSummary.builder()
                .id(issue.getId())
                .title(issue.getTitle())
                .summary(issue.getSummary())
                .lastSeenAt(issue.getLastSeenAt())
                .articleCount(issue.getArticleCount())
                .publisherCount(issue.getPublisherCount())
                .independentContentCount(issue.getIndependentContentCount())
                .topicName(issue.getTopic().getName())
                .entities(issue.getEntities())
                .build();
    }

    private Map<Long, FindingRepository.ReportCount> countsByRun(List<NewsReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> runIds = reports.stream().map(report -> report.getRun().getId()).toList();
        return findingRepository.countForReports(runIds, sensitivityCalculator.highThreshold()).stream()
                .collect(Collectors.toMap(FindingRepository.ReportCount::getRunId, Function.identity()));
    }

    private Map<String, Long> orderedCounts(List<Finding> findings,
                                            Function<Finding, String> classifier,
                                            List<String> keyOrder) {
        Map<String, Long> actual = findings.stream()
                .collect(Collectors.groupingBy(classifier, Collectors.counting()));
        Map<String, Long> ordered = new LinkedHashMap<>();
        keyOrder.forEach(key -> ordered.put(key, actual.getOrDefault(key, 0L)));
        actual.forEach(ordered::putIfAbsent);
        return ordered;
    }

    private Map<String, Long> emptyOrderedCounts(List<String> keyOrder) {
        Map<String, Long> counts = new LinkedHashMap<>();
        keyOrder.forEach(key -> counts.put(key, 0L));
        return counts;
    }

    LocalDateTime parseDateTime(String value, boolean upperBoundary) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        try {
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ApiTimeZone.ZONE)
                    .toLocalDateTime();
        } catch (DateTimeException ignored) {
            // 오프셋 없는 local datetime과 명세 예시의 date-only를 이어서 시도한다.
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeException ignored) {
            // date-only를 이어서 시도한다.
        }
        try {
            LocalDate date = LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
            return upperBoundary
                    ? date.plusDays(1).atStartOfDay().minusNanos(1)
                    : date.atStartOfDay();
        } catch (DateTimeException exception) {
            throw badRequest("from과 to는 ISO-8601 형식이어야 합니다.");
        }
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw badRequest("page는 0 이상이어야 합니다.");
        }
        if (size < PageResponse.MIN_SIZE || size > PageResponse.MAX_SIZE) {
            throw badRequest("size는 1 이상 100 이하여야 합니다.");
        }
    }

    private GeneralException badRequest(String message) {
        return new GeneralException(GeneralErrorCode.BAD_REQUEST, message);
    }
}
