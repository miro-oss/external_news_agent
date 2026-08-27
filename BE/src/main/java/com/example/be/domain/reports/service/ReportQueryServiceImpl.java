package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingCategory;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.ChangeType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryServiceImpl implements ReportQueryService {

    private static final String DELIVERY_STATUS_NOT_SENT = "NOT_SENT";

    private final NewsReportRepository reportRepository;
    private final FindingRepository findingRepository;
    private final DeliveryLogRepository deliveryLogRepository;

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
        List<ReportResDTO.Summary> content = reports.getContent().stream()
                .map(report -> toSummary(report, counts.get(report.getRun().getId())))
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

    private ReportResDTO.Summary toSummary(NewsReport report, FindingRepository.ReportCount count) {
        return ReportResDTO.Summary.builder()
                .id(report.getId())
                .runId(report.getRun().getId())
                .title(report.getTitle())
                .generatedAt(toOffset(report.getGeneratedAt()))
                .modelName(report.getModelName())
                .findingCount(count == null ? 0 : count.getFindingCount())
                .highRiskCount(count == null ? 0 : count.getHighRiskCount())
                .deliveryStatus(deliveryStatus(report.getId()))
                .build();
    }

    private String deliveryStatus(Long reportId) {
        if (deliveryLogRepository.existsByReportIdAndStatus(reportId, DeliveryStatus.SENT)) {
            return "SENT";
        }
        return deliveryLogRepository.existsByReportId(reportId) ? "FAILED" : DELIVERY_STATUS_NOT_SENT;
    }

    private ReportResDTO.Detail toDetail(NewsReport report, boolean includeFindings) {
        Long runId = report.getRun().getId();
        List<Finding> findings = includeFindings
                ? ReportFindingOrder.sort(findingRepository.findForReportByRunId(runId))
                : null;
        ReportResDTO.SummaryStats summaryStats = includeFindings
                ? toStats(findings)
                : toStatsFromCounts(findingRepository.countStatsByRunId(runId));
        return ReportResDTO.Detail.builder()
                .id(report.getId())
                .runId(runId)
                .title(report.getTitle())
                .markdownBody(report.getMarkdownBody())
                .modelName(report.getModelName())
                .generatedAt(toOffset(report.getGeneratedAt()))
                .summaryStats(summaryStats)
                .findings(includeFindings ? findings.stream().map(this::toFinding).toList() : null)
                .build();
    }

    private ReportResDTO.SummaryStats toStats(List<Finding> findings) {
        Map<String, Long> byRiskLevel = orderedCounts(findings,
                finding -> finding.getRiskLevel().toApiValue(), List.of("high", "medium", "low"));
        Map<String, Long> byCategory = orderedCounts(findings, Finding::getCategory,
                FindingCategory.ORDERED_VALUES);
        return ReportResDTO.SummaryStats.builder()
                .findingCount(findings.size())
                .newCount(findings.stream().filter(finding -> finding.getChangeType() == ChangeType.NEW).count())
                .updatedCount(findings.stream().filter(finding -> finding.getChangeType() == ChangeType.UPDATED).count())
                .byRiskLevel(byRiskLevel)
                .byCategory(byCategory)
                .build();
    }

    private ReportResDTO.SummaryStats toStatsFromCounts(List<FindingRepository.ReportStatsCount> counts) {
        Map<String, Long> byRiskLevel = emptyOrderedCounts(List.of("high", "medium", "low"));
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
            byRiskLevel.merge(count.getRiskLevel().toApiValue(), value, Long::sum);
            byCategory.merge(count.getCategory(), value, Long::sum);
        }
        return ReportResDTO.SummaryStats.builder()
                .findingCount(findingCount)
                .newCount(newCount)
                .updatedCount(updatedCount)
                .byRiskLevel(byRiskLevel)
                .byCategory(byCategory)
                .build();
    }

    private ReportResDTO.Finding toFinding(Finding finding) {
        return ReportResDTO.Finding.builder()
                .id(finding.getId())
                .articleId(finding.getArticle().getId())
                .articleTitle(finding.getArticle().getTitle())
                .canonicalUrl(finding.getArticle().getCanonicalUrl())
                .changeType(finding.getChangeType().name())
                .summary(finding.getSummary())
                .keyPoints(ReportEvidencePolicy.supportedKeyPoints(finding).stream()
                        .map(point -> point.text())
                        .toList())
                .intent(finding.getIntent())
                .sentiment(finding.getSentiment().toApiValue())
                .riskLevel(finding.getRiskLevel().toApiValue())
                .relevance(finding.getRelevance().toApiValue())
                .category(finding.getCategory())
                .build();
    }

    private Map<Long, FindingRepository.ReportCount> countsByRun(List<NewsReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<Long> runIds = reports.stream().map(report -> report.getRun().getId()).toList();
        return findingRepository.countForReports(runIds).stream()
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
