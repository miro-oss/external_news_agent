package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.briefing.ReportBriefing;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportChangesQueryService;
import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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
 * reading sections and a linked company performance/filings appendix. Standalone article analysis,
 * collection notes and per-quarter financial source appendices are intentionally omitted.
 */
@Component
@RequiredArgsConstructor
public class ReportPdfRenderer {
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
        try (PDDocument document = new PDDocument(); ReportPdfLayout pdf = new ReportPdfLayout(document)) {
            document.getDocumentInformation().setTitle(report.getTitle());
            document.getDocumentInformation().setAuthor("BISTelligence");
            pdf.title(report.getTitle());
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
            if (content != null) briefing(pdf, ReportBriefing.from(view), companies);
            if (unavailableAudience != null) pdf.muted(NotificationBriefingSection.unavailable(unavailableAudience));
            ReportPdfInsightSection.brief(pdf, insight, view);
            NotificationChangesSection.pdfBrief(pdf, changes);
            NotificationKeywordSection.pdf(pdf, keywords);
            if (content != null) {
                pdf.internalLink("이슈와 근거 자세히 보기", "report-detail");
                if (insight != null) pdf.internalLink("관점 분석 자세히 보기", "insight-detail");
                pdf.pageBreak();
            }
            pdf.anchor("report-detail");
            if (content == null) legacy(pdf, view.readingContent().markdownBody());
            else body(pdf, content, findings, coverage, companies);
            if (insight != null) {
                pdf.reserve(110);
                pdf.anchor("insight-detail");
            }
            ReportPdfInsightSection.write(pdf, insight, view);
            NotificationChangesSection.pdfDetails(pdf, changes);
            Map<String, Integer> companyPages = companyData(pdf, companies);
            return new Result(pdf.save(), companyPages);
        } catch (IOException exception) {
            throw new UncheckedIOException("보고서 PDF를 준비하지 못했습니다.", exception);
        }
    }

    private void briefing(ReportPdfLayout pdf, ReportBriefing brief, List<CompanyAttachment> companies) throws IOException {
        pdf.section("핵심 브리핑");
        int number = 0;
        for (var item : brief.items()) {
            pdf.reserve(110);
            pdf.subheading((++number) + ". " + item.title());
            pdf.paragraph(item.summary());
            NotificationCompanySection.pdf(pdf, item.sourceFindingIds(), companies);
            if (item.significance() != null && !item.significance().isBlank()) pdf.muted("읽을 포인트 · " + item.significance());
        }
        if (!brief.watchItems().isEmpty()) {
            pdf.subheading("다음 확인점");
            for (var item : brief.watchItems()) pdf.bullet(item.topic() + " · " + item.reason());
        }
        if (brief.omittedItemCount() > 0) pdf.muted("나머지 " + brief.omittedItemCount() + "개 항목은 뒤쪽 이슈와 근거에서 확인하세요.");
        if (brief.items().isEmpty() && brief.watchItems().isEmpty() && brief.omittedItemCount() == 0)
            pdf.muted("짧은 브리핑의 연결 근거가 없습니다. 뒤쪽의 저장된 요약을 확인하세요.");
    }

    private void body(ReportPdfLayout pdf, ReportContent content, Map<Long, Finding> findings,
                      ReportCoverageDTO.View coverage, List<CompanyAttachment> companies) throws IOException {
        pdf.section("핵심 요약");
        pdf.internalLink("핵심 브리핑으로 돌아가기", "summary");
        if (content.executiveSummary().isEmpty()) pdf.paragraph("근거가 확인된 요약이 없습니다.");
        for (String summary : content.executiveSummary()) pdf.bullet(summary);
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
            NotificationCompanySection.pdf(pdf, event.sourceFindingIds(), companies);
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

    /** Reads only the report's stored company snapshot, including its recent disclosures. */
    private Map<String, Integer> companyData(ReportPdfLayout pdf, List<CompanyAttachment> companies) throws IOException {
        if (companies.isEmpty()) return Map.of();
        pdf.section("회사별 부록 바로가기");
        pdf.muted("보고서에 연결해 둔 회사의 자료입니다. 회사명을 누르면 해당 부록으로 이동합니다.");
        for (int index = 0; index < companies.size(); index++)
            pdf.internalLink(companies.get(index).company().corpName(), "company-" + index);
        Map<String, Integer> pages = new LinkedHashMap<>();
        for (int index = 0; index < companies.size(); index++) {
            CompanyAttachment entry = companies.get(index);
            pdf.pageBreak();
            pdf.anchor("company-" + index);
            pages.putIfAbsent(entry.company().corpCode(), pdf.destinationPage("company-" + index));
            pdf.section("회사 실적 · 공시");
            pdf.subheading(entry.company().corpName());
            pdf.muted(entry.company().stockCode() == null || entry.company().stockCode().isBlank() ? "비상장" : entry.company().stockCode());
            pdf.internalLink("핵심 요약으로 돌아가기", "summary");
            if (!("READY".equals(entry.status()) || "PARTIAL".equals(entry.status())) || entry.data() == null) {
                pdf.paragraph("표시할 저장된 실적·공시 자료가 없습니다.");
                continue;
            }
            FinancialData data = entry.data();
            pdf.muted(("CFS".equals(data.fsDiv()) ? "연결재무제표" : "OFS".equals(data.fsDiv()) ? "별도재무제표" : "재무 기준 미확인")
                    + (data.fetchedAt() == null ? " · 자료 확인 시각 미상" : " · 자료 확인 "
                    + data.fetchedAt().atZoneSameInstant(ApiTimeZone.ZONE).format(DATE_TIME) + " (한국시간)"));
            if (data.quarters().isEmpty()) pdf.paragraph("표시할 분기 실적 자료가 없습니다.");
            else {
                pdf.chart(data.quarters(), data.currency());
                boolean won = "KRW".equals(data.currency());
                String unit = won ? "억원" : data.currency() == null ? "통화 미확인" : data.currency();
                pdf.muted("분기별 상세 실적 · 표 금액 단위: " + unit
                        + (won ? " · 소수 둘째 자리까지 반올림합니다." : " · 원 금액을 그대로 표시합니다."));
                pdf.muted((won ? "절댓값이 0보다 크고 0.01억원 미만인 금액은 범위로 표시합니다. " : "")
                        + "자료 없음은 0이 아닙니다.");
                List<List<String>> rows = new ArrayList<>();
                for (var quarter : data.quarters()) rows.add(List.of(quarter.year() + "년 " + quarter.quarter() + "분기"
                                + (quarter.derived() ? "\n산출값" : ""), amount(quarter.revenue(), data.currency()), amount(quarter.operatingProfit(), data.currency()),
                        quarter.operatingMargin() == null ? "자료 없음" : quarter.operatingMargin().stripTrailingZeros().toPlainString() + "%"));
                pdf.table(List.of("분기", "매출액 (" + unit + ")", "영업이익 (" + unit + ")", "영업이익률"), rows, new float[] { 1.1f, 1.8f, 1.8f, 1 });
                if (data.quarters().stream().anyMatch(com.example.be.domain.dart.model.DartModels.Quarter::derived))
                    pdf.muted("4분기 산출값은 같은 회계 기준의 연간 금액에서 3분기 누적 금액을 뺀 값입니다.");
            }
            for (String note : data.notes()) pdf.muted(note);
            pdf.muted("실적은 회사 전체 기준입니다. 자료 확인 시 확보한 내용을 보존하며, 이후 정정공시가 반영된 최신 수치와 다를 수 있습니다.");
            filingTable(pdf, entry);
        }
        return pages;
    }

    private void filingTable(ReportPdfLayout pdf, CompanyAttachment entry) throws IOException {
        pdf.reserve(130);
        pdf.subheading("공시 상세");
        pdf.muted("확인 범위: 저장한 최근 공시 최대 5건 + 12분기 실적의 근거 공시");
        pdf.muted("직접 인용은 기사에 명시된 접수번호가 일치한다는 뜻이며 기사 전체의 사실 검증을 뜻하지 않습니다.");
        if (entry.filingRows().stream().noneMatch(row -> "DIRECT_REFERENCE".equals(row.relevance())))
            pdf.muted("확보한 공시에서 직접 인용 확인 안 됨");
        if (entry.filingRows().isEmpty()) {
            pdf.paragraph("저장된 공시 자료가 없습니다.");
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (var row : entry.filingRows()) {
            Filing filing = row.filing();
            rows.add(List.of(filing.filedAt() == null ? "미확인" : filing.filedAt(),
                    (filing.corrected() && (filing.reportName() == null || !filing.reportName().contains("정정")) ? "[정정] " : "")
                            + (filing.reportName() == null ? "공시 원문" : filing.reportName())
                            + "\n접수 " + filing.rceptNo(),
                    NotificationCompanySection.roles(row), NotificationCompanySection.relevance(row),
                    DartFilingLinks.isSafe(filing) ? "원문" : "미확인"));
            urls.add(DartFilingLinks.isSafe(filing) ? filing.url() : "");
        }
        pdf.linkedTable(List.of("접수일", "공시명 · 접수번호", "자료 구분", "기사와의 연결", "DART"), rows,
                new float[]{1.2f, 3.3f, 1.3f, 1.5f, 0.65f}, urls);
    }

    /**
     * Displays KRW in hundred-million-won units with two-decimal HALF_UP rounding. Nonzero amounts
     * below 0.01 of that unit retain their sign as an interval; other currencies keep their original
     * decimal precision. Missing or invalid values remain distinct from an actual zero.
     */
    static String amount(String value, String currency) {
        if (value == null || !value.matches("-?\\d+(?:\\.\\d+)?")) return "자료 없음";
        BigDecimal amount = new BigDecimal(value);
        if ("KRW".equals(currency)) {
            amount = amount.movePointLeft(8);
            if (amount.signum() != 0 && amount.abs().compareTo(new BigDecimal("0.01")) < 0)
                return amount.signum() < 0 ? "-0.01 < 값 < 0" : "0 < 값 < 0.01";
            amount = amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(java.util.Locale.KOREA));
        format.setMinimumFractionDigits(Math.max(0, amount.scale()));
        format.setMaximumFractionDigits(Math.max(0, amount.scale()));
        return format.format(amount);
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
