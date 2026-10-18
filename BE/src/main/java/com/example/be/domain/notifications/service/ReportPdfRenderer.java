package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
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
 * Offline attachment containing company performance, summaries, important events, watch items and
 * the selected audience's reading sections. Standalone article analysis, collection notes and
 * disclosure listings are intentionally omitted; the PDF is not an export of every web section.
 */
@Component
@RequiredArgsConstructor
public class ReportPdfRenderer {
    private final ReportDartService dart;
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
        try (PDDocument document = new PDDocument(); ReportPdfLayout pdf = new ReportPdfLayout(document)) {
            document.getDocumentInformation().setTitle(report.getTitle());
            document.getDocumentInformation().setAuthor("BISTelligence");
            pdf.title(report.getTitle());
            if (report.getGeneratedAt() != null) pdf.muted("보고서 작성 " + report.getGeneratedAt().format(DATE_TIME) + " (한국시간)");
            if (report.getReportDate() != null) pdf.muted("보고서 기간 " + report.getReportDate()
                    + (report.getReportEndDate() == null ? "" : " ~ " + report.getReportEndDate()));
            financials(pdf, report);

            Map<Long, Finding> findings = new LinkedHashMap<>();
            view.findings().forEach(finding -> findings.putIfAbsent(finding.getId(), finding));
            ReportContent content = view.readingContent().structuredContent();
            if (content == null) legacy(pdf, view.readingContent().markdownBody());
            else body(pdf, content, findings);
            ReportPdfInsightSection.write(pdf, insight, view);
            return pdf.save();
        } catch (IOException exception) {
            throw new UncheckedIOException("보고서 PDF를 준비하지 못했습니다.", exception);
        }
    }

    private void body(ReportPdfLayout pdf, ReportContent content, Map<Long, Finding> findings) throws IOException {
        pdf.section("핵심 요약");
        if (content.executiveSummary().isEmpty()) pdf.paragraph("근거가 확인된 요약이 없습니다.");
        for (String summary : content.executiveSummary()) pdf.bullet(summary);
        pdf.section("중요 이벤트");
        var events = content.importantEvents().stream().filter(event -> !event.sourceFindingIds().isEmpty()
                && findings.keySet().containsAll(event.sourceFindingIds())).toList();
        if (events.isEmpty()) pdf.paragraph("중요 이벤트가 없습니다.");
        for (var event : events) {
            pdf.subheading(event.title());
            pdf.paragraph(event.summaryKo());
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

    /** Reads the report's stored financial snapshot without refreshing it or including filing lists. */
    private void financials(ReportPdfLayout pdf, NewsReport report) throws IOException {
        if (report.getReportScope() != ReportScope.RUN) return;
        var result = dart.getStored(report);
        if (result == null || result.companies().isEmpty()) return;
        pdf.reserve(345);
        pdf.section("회사 실적");
        for (CompanyAttachment entry : result.companies()) {
            if (entry.data() != null && !entry.data().quarters().isEmpty()
                    && ("READY".equals(entry.status()) || "PARTIAL".equals(entry.status()))) pdf.reserve(300);
            pdf.subheading(entry.company().corpName());
            pdf.muted(entry.company().stockCode() == null || entry.company().stockCode().isBlank() ? "비상장" : entry.company().stockCode());
            if (!("READY".equals(entry.status()) || "PARTIAL".equals(entry.status())) || entry.data() == null) {
                pdf.paragraph("표시할 저장된 실적 자료가 없습니다.");
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
        }
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
