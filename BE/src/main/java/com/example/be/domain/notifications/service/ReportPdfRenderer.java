package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.briefing.ReportBriefing;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportChangesQueryService;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.coverage.ReportCoverageDTO;
import com.example.be.domain.reports.coverage.ReportCoverageService;
import com.example.be.domain.reports.keyword.ReportKeywordDTO;
import com.example.be.domain.reports.keyword.ReportKeywordService;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline attachment containing summaries, important events, watch items, the selected audience's
 * reading sections and a compact company performance/filings appendix. Standalone article analysis,
 * collection notes and per-quarter financial source appendices are intentionally omitted.
 */
@Component
@RequiredArgsConstructor
public class ReportPdfRenderer {
    private NotificationArticleStatistics articleStatistics;
    @org.springframework.beans.factory.annotation.Autowired
    public void setArticleStatistics(NotificationArticleStatistics articleStatistics) { this.articleStatistics = articleStatistics; }
    private final ReportDartService dart;
    private ReportCoverageService coverageService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCoverageService(ReportCoverageService coverageService) { this.coverageService = coverageService; }
    private ReportKeywordService keywordService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setKeywordService(ReportKeywordService keywordService) { this.keywordService = keywordService; }
    private ReportChangesQueryService changesService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setChangesService(ReportChangesQueryService changesService) { this.changesService = changesService; }
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]\\r\\n]+)]\\(([^)\\r\\n]+)\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\r\\n]+)>");
    private static final Pattern WEEKLY_CHRONOLOGY = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}\\s+(?:주제별 분석 결과|일일 보고서):");

    /**
     * Builds an in-memory PDF using embedded fonts and saved report data only.
     *
     * @param report report metadata and the identity of its stored financial snapshot
     * @param view final recipient-visible content after local reviews and personal exclusions;
     *             raw report content must not replace this projection
     * @param insight the selected, already stored audience analysis, or null to omit it;
     *                its claims are filtered again against the supplied view
     * @return complete PDF bytes ready for attachment to this recipient's message
     * @throws UncheckedIOException if the attachment cannot be prepared completely
     */
    public byte[] render(NewsReport report, ReportEventFeedbackProjection.View view,
                         ReportInsightDTO.AudienceInsight insight) {
        return renderDetailed(report, view, insight).bytes();
    }

    public record Result(byte[] bytes, Map<String, Integer> companyPages) {
        public Result { bytes = bytes.clone(); companyPages = Map.copyOf(companyPages); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public Result renderDetailed(NewsReport report, ReportEventFeedbackProjection.View view,
                                 ReportInsightDTO.AudienceInsight insight) {
        var coverage = coverageService == null ? null : coverageService.project(report, view);
        return renderDetailed(report, view, insight, coverage);
    }

    public Result renderDetailed(NewsReport report, ReportEventFeedbackProjection.View view,
                                 ReportInsightDTO.AudienceInsight insight, ReportCoverageDTO.View coverage) {
        return renderDetailed(report, view, insight, coverage, null);
    }

    public Result renderDetailed(NewsReport report, ReportEventFeedbackProjection.View view,
                                 ReportInsightDTO.AudienceInsight insight, ReportCoverageDTO.View coverage,
                                 Audience unavailableAudience) {
        return renderDetailed(report, view, insight, coverage, unavailableAudience,
                keywordService == null ? null : keywordService.projectForDelivery(report, view));
    }

    public Result renderDetailed(NewsReport report, ReportEventFeedbackProjection.View view,
                                 ReportInsightDTO.AudienceInsight insight, ReportCoverageDTO.View coverage,
                                 Audience unavailableAudience, ReportKeywordDTO.View keywords) {
        return renderDetailed(report, view, insight, coverage, unavailableAudience, keywords,
                changesService == null ? null : changesService.projectForDelivery(report, view, false));
    }

    public Result renderDetailed(NewsReport report, ReportEventFeedbackProjection.View view,
                                 ReportInsightDTO.AudienceInsight insight, ReportCoverageDTO.View coverage,
                                 Audience unavailableAudience, ReportKeywordDTO.View keywords, ReportChanges changes) {
        var stored = dart.getStored(report);
        var companies = NotificationCompanyVisibility.visible(report, view, stored == null ? List.of() : stored.companies());
        return renderDetailed(report, view, insight, coverage, unavailableAudience, keywords, changes, companies);
    }

    public Result renderDetailed(NewsReport report, ReportEventFeedbackProjection.View view,
                                 ReportInsightDTO.AudienceInsight insight, ReportCoverageDTO.View coverage,
                                 Audience unavailableAudience, ReportKeywordDTO.View keywords, ReportChanges changes,
                                 List<CompanyAttachment> companies) {
        companies = NotificationCompanyVisibility.visible(report, view, companies);
        try (PDDocument document = new PDDocument(); ReportPdfLayout pdf = new ReportPdfLayout(document)) {
            document.getDocumentInformation().setTitle(report.getTitle());
            document.getDocumentInformation().setAuthor("BISTelligence");
            pdf.title(report.getTitle());
            if (articleStatistics != null) pdf.muted(articleStatistics.summary(report));
            if (report.getGeneratedAt() != null) pdf.muted("보고서 작성 " + report.getGeneratedAt().format(DATE_TIME) + " (한국시간)");
            if (report.getReportDate() != null) pdf.muted("보고서 기간 " + report.getReportDate()
                    + (report.getReportEndDate() == null ? "" : " ~ " + report.getReportEndDate()));
            pdf.muted("보도량: " + (coverage == null ? ReportCoverageService.periodLabel(report) : coverage.periodLabel())
                    + " · 매체 수는 원문 사이트 기준이며 독립 검증 건수가 아닙니다.");
            pdf.reserve(100);
            pdf.anchor("summary");

            Map<Long, Finding> findings = new LinkedHashMap<>();
            view.findings().forEach(finding -> findings.putIfAbsent(finding.getId(), finding));
            ReportContent content = view.readingContent().structuredContent();
            if (content != null) briefing(pdf, view);
            if (unavailableAudience != null) pdf.muted(NotificationBriefingSection.unavailable(unavailableAudience));
            ReportPdfInsightSection.brief(pdf, insight, view);
            NotificationChangesSection.pdfBrief(pdf, changes);
            NotificationKeywordSection.pdf(pdf, keywords);
            if (content != null) {
                pdf.internalLink("이슈와 근거 자세히 보기", "report-detail");
                pdf.pageBreak();
            }
            pdf.anchor("report-detail");
            if (content == null) legacy(pdf, view.readingContent().markdownBody());
            else body(pdf, content, findings, coverage);
            NotificationChangesSection.pdfDetails(pdf, changes);
            Map<String, Integer> companyPages = companyData(pdf, companies);
            return new Result(pdf.save(), companyPages);
        } catch (IOException exception) {
            throw new UncheckedIOException("보고서 PDF를 준비하지 못했습니다.", exception);
        }
    }

    private void briefing(ReportPdfLayout pdf, ReportEventFeedbackProjection.View view) throws IOException {
        pdf.section("핵심 브리핑");
        int number = 0;
        var titles = NotificationBriefingSection.reportTitles(view);
        for (String title : titles) pdf.subheading((++number) + ". " + title);
        if (titles.isEmpty()) pdf.muted(NotificationBriefingSection.emptyReportMessage(view));
    }

    private void body(ReportPdfLayout pdf, ReportContent content, Map<Long, Finding> findings,
                      ReportCoverageDTO.View coverage) throws IOException {
        pdf.internalLink("핵심 브리핑으로 돌아가기", "summary");
        pdf.reserve(140);
        pdf.section("중요 이벤트");
        var events = content.importantEvents().stream().filter(event -> !event.sourceFindingIds().isEmpty()
                && findings.keySet().containsAll(event.sourceFindingIds())).toList();
        if (events.isEmpty()) pdf.paragraph("중요 이벤트가 없습니다.");
        for (var event : events) {
            pdf.subheading(event.title());
            var counts = coverage == null ? ReportCoverageDTO.unavailable(event.sourceFindingIds())
                    : coverage.forSources(event.sourceFindingIds());
            pdf.muted(NotificationCoverage.label(counts));
            pdf.paragraph(event.summaryKo());
            String significance = ReportBriefing.readingSignificance(event.significance());
            if (significance != null && !significance.isBlank()) pdf.keyValue("읽을 포인트", significance);
            representativeArticles(pdf, counts);
            pdf.muted("요약의 직접 근거");
            sources(pdf, event.sourceFindingIds(), findings);
        }
        var watches = content.watchItems().stream().filter(item -> !item.sourceFindingIds().isEmpty()
                && findings.keySet().containsAll(item.sourceFindingIds())).toList();
        if (!watches.isEmpty()) {
            pdf.section("관찰 항목");
            for (var item : watches) {
                pdf.subheading(item.topic());
                pdf.paragraph(item.reason());
                sources(pdf, item.sourceFindingIds(), findings);
            }
        }
        // Standalone findings and collection notes are intentionally outside the attachment's scope.
    }

    private void representativeArticles(ReportPdfLayout pdf, ReportCoverageDTO.EventCoverage coverage) throws IOException {
        if (coverage.representativeArticles().isEmpty()) return;
        pdf.muted("대표 보도");
        for (var article : coverage.representativeArticles().stream().limit(2).toList()) {
            String label = article.title() + " · " + NotificationCoverage.metadata(article);
            if (ReportPdfLayout.safeUrl(article.canonicalUrl())) pdf.link(label, article.canonicalUrl());
            else pdf.muted(label);
        }
    }

    private void sources(ReportPdfLayout pdf, List<Long> ids, Map<Long, Finding> findings) throws IOException {
        Set<Long> articles = new HashSet<>();
        for (Long id : ids) {
            Finding finding = findings.get(id);
            if (finding == null || finding.getArticle() == null || !articles.add(finding.getArticle().getId())) continue;
            String url = finding.getArticle().getCanonicalUrl();
            if (ReportPdfLayout.safeUrl(url)) pdf.link(url, url);
        }
        pdf.gap(3);
    }

    /** The final section uses only the report's saved, title-matched companies. */
    private Map<String, Integer> companyData(ReportPdfLayout pdf, List<CompanyAttachment> companies) throws IOException {
        if (companies.isEmpty()) return Map.of();
        Map<String, Integer> pages = new LinkedHashMap<>();
        boolean first = true;
        for (CompanyAttachment entry : companies) {
            boolean ready = ("READY".equals(entry.status()) || "PARTIAL".equals(entry.status())) && entry.data() != null;
            var quarters = ready ? CompanyFinancialBrief.quarters(entry.data()) : List.<Quarter>of();
            String title = entry.company().corpName() + " · " + entry.company().stockCode();
            FinancialData data = entry.data();
            String metadata = ready ? CompanyFinancialBrief.basis(data.fsDiv()) + " · 단위: " + CompanyFinancialBrief.unit(data.currency())
                    + ("PARTIAL".equals(entry.status()) ? " · 일부 자료 없음" : "")
                    + (data.fetchedAt() == null ? " · 확인 시각 미상" : " · 확인 "
                    + data.fetchedAt().atZoneSameInstant(ApiTimeZone.ZONE).format(DATE_TIME) + " (한국시간)")
                    : switch (entry.status() == null ? "PENDING" : entry.status()) {
                        case "FAILED" -> "공시 자료 확인 실패";
                        case "DISABLED" -> "공시 자료 확인 불가";
                        case "READY", "PARTIAL" -> "공시 자료 없음";
                        default -> "공시 자료 준비 중";
                    };
            List<List<String>> rows = new ArrayList<>();
            for (var quarter : quarters) rows.add(List.of(quarter.year() + "년 " + quarter.quarter() + "분기"
                            + (quarter.derived() ? " · 산출값" : ""), amount(quarter.revenue(), data.currency()),
                    amount(quarter.operatingProfit(), data.currency())));
            pdf.reserveFinancialBlock(title, metadata, quarters, ready ? data.currency() : null, rows, first);
            if (first) { pdf.section("회사 실적 · 공시"); first = false; }
            pages.putIfAbsent(entry.company().corpCode(), pdf.pageNumber());
            pdf.subheading(title);
            pdf.muted(metadata);
            if (!ready) continue;
            if (quarters.isEmpty()) pdf.muted("표시할 분기 실적 자료가 없습니다.");
            else {
                pdf.chart(quarters, data.currency());
                pdf.financialTable(rows);
            }
            // Preserve caveats for the displayed quarters and whole-series accounting constraints only.
            for (String note : data.notes()) {
                if (note == null || note.isBlank()) continue;
                var period = Pattern.compile("^(\\d{4})년 ([1-4])분기").matcher(note);
                if (period.find() && quarters.stream().noneMatch(q -> q.year() == Integer.parseInt(period.group(1))
                        && q.quarter() == Integer.parseInt(period.group(2)))) continue;
                if (note.endsWith("일부 실적 자료가 없습니다.")) continue;
                pdf.muted(note);
            }
            latestFiling(pdf, entry);
            pdf.gap(8);
        }
        boolean hasFinancials = companies.stream().filter(entry -> "READY".equals(entry.status()) || "PARTIAL".equals(entry.status()))
                .anyMatch(entry -> entry.data() != null && !CompanyFinancialBrief.quarters(entry.data()).isEmpty());
        if (hasFinancials) {
            boolean won = companies.stream().filter(entry -> "READY".equals(entry.status()) || "PARTIAL".equals(entry.status()))
                    .anyMatch(entry -> entry.data() != null && "KRW".equals(entry.data().currency()) && !CompanyFinancialBrief.quarters(entry.data()).isEmpty());
            pdf.muted("자료 없음은 0이 아닙니다." + (won ? " KRW 금액은 소수 둘째 자리 반올림, 미세값은 원값을 유지합니다." : ""));
            if (companies.stream().filter(entry -> ("READY".equals(entry.status()) || "PARTIAL".equals(entry.status())) && entry.data() != null)
                    .flatMap(entry -> CompanyFinancialBrief.quarters(entry.data()).stream()).anyMatch(Quarter::derived))
                pdf.muted("산출값: 같은 회계 기준의 연간 금액 − 3분기 누적 금액.");
        }
        return pages;
    }

    private void latestFiling(ReportPdfLayout pdf, CompanyAttachment entry) throws IOException {
        var filing = CompanyFinancialBrief.latestFiling(entry.data());
        if (filing == null) { pdf.muted("최근 공시 · 저장된 공시 자료가 없습니다."); return; }
        String title = filing.reportName() == null || filing.reportName().isBlank() ? "공시 원문" : filing.reportName();
        title = NotificationRenderer.prose(title);
        if (title.codePointCount(0, title.length()) > 100) title = title.substring(0, title.offsetByCodePoints(0, 99)) + "…";
        String label = "최근 공시 · " + (filing.filedAt() == null ? "접수일 미확인" : filing.filedAt()) + " · "
                + (filing.corrected() && !title.contains("정정") ? "[정정] " : "") + title;
        if (DartFilingLinks.isSafe(filing)) pdf.link(label, filing.url());
        else pdf.muted(label);
    }

    /** Shared delivery units preserve missing, zero, negative and sub-rounding monetary values. */
    static String amount(String value, String currency) {
        return CompanyFinancialBrief.number(value, currency);
    }

    /**
     * Applies the same omissions to Markdown-only reports, including the older article-analysis and
     * excluded-issue appendix names grouped as 기타 분석 in the web reader. Nested headings remain
     * omitted until the next sibling or parent section; headings inside fenced text are not sections.
     */
    private void legacy(ReportPdfLayout pdf, String markdown) throws IOException {
        if (markdown == null || markdown.isBlank()) { pdf.paragraph("표시할 보고서 내용이 없습니다."); return; }
        int skippedLevel = 0;
        String fence = null;
        boolean firstContent = true;
        StringBuilder paragraph = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            String trimmed = line.strip();
            var fenceMatch = Pattern.compile("^(`{3,}|~{3,}).*").matcher(trimmed);
            if (fenceMatch.matches()) {
                if (fence == null) fence = fenceMatch.group(1);
                else if (fenceMatch.group(1).charAt(0) == fence.charAt(0) && fenceMatch.group(1).length() >= fence.length()) fence = null;
                continue;
            }
            var heading = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$").matcher(line);
            if (fence == null && heading.matches()) {
                flushLegacy(pdf, paragraph);
                int level = heading.group(1).length();
                String title = heading.group(2);
                if (skippedLevel > 0 && level <= skippedLevel) skippedLevel = 0;
                if (title.matches(".*(?:기타\\s*분석|기사별\\s*분석|보고서\\s*제외|수집\\s*상태|수집\\s*및\\s*출처\\s*참고|먼저\\s*살펴볼\\s*이슈).*")
                        && skippedLevel == 0) skippedLevel = level;
                if (skippedLevel > 0) continue;
                if (firstContent && level == 1) { firstContent = false; continue; }
                firstContent = false;
                if (level <= 2) pdf.section(plain(title)); else pdf.subheading(plain(title));
            } else if (skippedLevel == 0) {
                if (!trimmed.isEmpty()) firstContent = false;
                if (trimmed.isEmpty()) flushLegacy(pdf, paragraph);
                else if (trimmed.matches("^[-*+]\\s+.*")) {
                    flushLegacy(pdf, paragraph);
                    legacyText(pdf, trimmed.substring(2), true);
                } else paragraph.append(paragraph.isEmpty() ? "" : "\n").append(line);
            }
        }
        flushLegacy(pdf, paragraph);
    }

    private void flushLegacy(ReportPdfLayout pdf, StringBuilder paragraph) throws IOException {
        if (!paragraph.isEmpty()) { legacyText(pdf, paragraph.toString(), false); paragraph.setLength(0); }
    }

    private void legacyText(ReportPdfLayout pdf, String text, boolean bullet) throws IOException {
        // Older weekly reports embed the saved evidence chronology after the event summary.
        String sourceText = text;
        var chronology = WEEKLY_CHRONOLOGY.matcher(text);
        if (chronology.find()) text = text.substring(0, chronology.start()).strip();
        if (!text.isBlank()) {
            if (bullet) pdf.bullet(plain(text)); else pdf.paragraph(plain(text));
        }
        var links = MARKDOWN_LINK.matcher(sourceText);
        while (links.find()) if (ReportPdfLayout.safeUrl(links.group(2))) pdf.link(links.group(2), links.group(2));
        var autolinks = AUTOLINK.matcher(sourceText);
        while (autolinks.find()) if (ReportPdfLayout.safeUrl(autolinks.group(1))) pdf.link(autolinks.group(1), autolinks.group(1));
    }

    private String plain(String value) {
        return MARKDOWN_LINK.matcher(value).replaceAll("$1").replaceAll("\\*\\*|__|`", "")
                .replaceAll("<(?:https?://[^>]+)>", "기사 원문 링크");
    }
}
