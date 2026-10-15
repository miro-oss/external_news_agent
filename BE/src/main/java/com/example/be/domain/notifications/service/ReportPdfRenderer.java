package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.dart.model.DartModels.Filing;
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
import java.net.URI;
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

/** Complete offline reading attachment built from the same final recipient-visible view as the email. */
@Component
@RequiredArgsConstructor
public class ReportPdfRenderer {
    private final ReportDartService dart;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]\\r\\n]+)]\\(([^)\\r\\n]+)\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<(https?://[^>\\r\\n]+)>");

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
            else body(pdf, content, findings, view.excludedEvidenceFindingIds());
            ReportPdfInsightSection.write(pdf, insight, view);
            return pdf.save();
        } catch (IOException exception) {
            throw new UncheckedIOException("보고서 PDF를 준비하지 못했습니다.", exception);
        }
    }

    private void body(ReportPdfLayout pdf, ReportContent content, Map<Long, Finding> findings,
                      Set<Long> excludedEvidence) throws IOException {
        pdf.section("핵심 요약");
        if (content.executiveSummary().isEmpty()) pdf.paragraph("근거가 확인된 요약이 없습니다.");
        for (String summary : content.executiveSummary()) pdf.bullet(summary);
        pdf.section("중요 이벤트");
        var events = content.importantEvents().stream().filter(event -> !event.sourceFindingIds().isEmpty()
                && findings.keySet().containsAll(event.sourceFindingIds())).toList();
        if (events.isEmpty()) pdf.paragraph("중요 이벤트가 없습니다.");
        Set<Long> referenced = new HashSet<>();
        for (var event : events) {
            referenced.addAll(event.sourceFindingIds());
            pdf.subheading(event.title());
            pdf.paragraph(event.summaryKo());
            if (event.significance() != null && !event.significance().equals(event.summaryKo())) pdf.paragraph(event.significance());
            sources(pdf, event.sourceFindingIds(), findings);
        }
        var watches = content.watchItems().stream().filter(item -> !item.sourceFindingIds().isEmpty()
                && findings.keySet().containsAll(item.sourceFindingIds())).toList();
        if (!watches.isEmpty()) {
            pdf.section("관찰 항목");
            for (var item : watches) {
                referenced.addAll(item.sourceFindingIds());
                pdf.subheading(item.topic());
                pdf.paragraph(item.reason());
                sources(pdf, item.sourceFindingIds(), findings);
            }
        }
        var other = findings.values().stream().filter(finding -> !referenced.contains(finding.getId()))
                .filter(finding -> !excludedEvidence.contains(finding.getId()))
                .filter(FindingEvidencePolicy::hasSupportedEvidence).toList();
        if (!other.isEmpty()) {
            pdf.section("기타 분석");
            for (Finding finding : other) {
                if (finding.getArticle() != null) pdf.subheading(finding.getArticle().getTitle());
                pdf.paragraph(FindingEvidencePolicy.reportSummary(finding));
                sources(pdf, List.of(finding.getId()), findings);
            }
        }
        // Collection notes and sourceNotes are intentionally absent from the reading attachment.
    }

    private void sources(ReportPdfLayout pdf, List<Long> ids, Map<Long, Finding> findings) throws IOException {
        Set<Long> articles = new HashSet<>();
        for (Long id : ids) {
            Finding finding = findings.get(id);
            if (finding == null || finding.getArticle() == null || !articles.add(finding.getArticle().getId())) continue;
            pdf.link("출처 · " + finding.getArticle().getTitle(), finding.getArticle().getCanonicalUrl());
        }
        pdf.gap(3);
    }

    private void financials(ReportPdfLayout pdf, NewsReport report) throws IOException {
        if (report.getReportScope() != ReportScope.RUN) return;
        var result = dart.getStored(report);
        if (result == null || result.companies().isEmpty()) return;
        pdf.reserve(345);
        pdf.section("회사 실적 · 공시");
        for (CompanyAttachment entry : result.companies()) {
            if (entry.data() != null && !entry.data().quarters().isEmpty()
                    && ("READY".equals(entry.status()) || "PARTIAL".equals(entry.status()))) pdf.reserve(300);
            pdf.subheading(entry.company().corpName());
            pdf.muted(entry.company().stockCode() == null || entry.company().stockCode().isBlank() ? "비상장" : entry.company().stockCode());
            if (!("READY".equals(entry.status()) || "PARTIAL".equals(entry.status())) || entry.data() == null) {
                pdf.paragraph("표시할 저장된 실적·공시 자료가 없습니다.");
                continue;
            }
            FinancialData data = entry.data();
            pdf.muted(("CFS".equals(data.fsDiv()) ? "연결재무제표" : "OFS".equals(data.fsDiv()) ? "별도재무제표" : "재무 기준 미확인")
                    + (data.fetchedAt() == null ? " · 자료 확인 시각 미상" : " · 자료 확인 "
                    + data.fetchedAt().atZoneSameInstant(ApiTimeZone.ZONE).format(DATE_TIME) + " (한국시간)"));
            Map<Filing, Integer> filings = new LinkedHashMap<>();
            for (var quarter : data.quarters()) for (Filing filing : quarter.sources())
                filings.computeIfAbsent(filing, key -> filings.size() + 1);
            if (data.quarters().isEmpty()) pdf.paragraph("표시할 분기 실적 자료가 없습니다.");
            else {
                pdf.chart(data.quarters(), data.currency());
                pdf.muted("분기별 상세 실적 · 표 금액 단위: " + ("KRW".equals(data.currency()) ? "원 (KRW)" : data.currency() == null ? "통화 미확인" : data.currency())
                        + " · 원 금액을 그대로 표시합니다. 자료 없음은 0이 아닙니다.");
                List<List<String>> rows = new ArrayList<>();
                for (var quarter : data.quarters()) rows.add(List.of(quarter.year() + "년 " + quarter.quarter() + "분기"
                                + (quarter.derived() ? "\n산출값" : ""), amount(quarter.revenue()), amount(quarter.operatingProfit()),
                        quarter.operatingMargin() == null ? "자료 없음" : quarter.operatingMargin().stripTrailingZeros().toPlainString() + "%",
                        quarter.sources().isEmpty() ? "자료 없음" : quarter.sources().stream().map(f -> "[" + filings.get(f) + "]").collect(java.util.stream.Collectors.joining(" "))));
                pdf.table(List.of("분기", "매출액", "영업이익", "영업이익률", "근거 공시"), rows, new float[] { 1.1f, 1.65f, 1.65f, 1, .85f });
                if (data.quarters().stream().anyMatch(com.example.be.domain.dart.model.DartModels.Quarter::derived))
                    pdf.muted("4분기 산출값은 같은 회계 기준의 연간 금액에서 3분기 누적 금액을 뺀 값입니다.");
            }
            if (!filings.isEmpty()) {
                pdf.subheading("실적 근거 공시");
                for (var entrySet : filings.entrySet()) filing(pdf, "[" + entrySet.getValue() + "] ", entrySet.getKey());
            }
            for (String note : data.notes()) pdf.muted(note);
            pdf.subheading("최근 공시");
            if (data.disclosures().isEmpty()) pdf.muted("확인된 공시가 없습니다.");
            for (Filing filing : data.disclosures()) filing(pdf, "", filing);
            pdf.muted("공시와 실적은 회사 전체 기준이며 뉴스 키워드로 필터하지 않습니다. 자료 확인 시 확보한 내용을 보존하며, 이후 정정공시가 반영된 최신 수치와 다를 수 있습니다.");
        }
    }

    static String amount(String value) {
        if (value == null || !value.matches("-?\\d+(?:\\.\\d+)?")) return "자료 없음";
        BigDecimal amount = new BigDecimal(value);
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(java.util.Locale.KOREA));
        format.setMinimumFractionDigits(Math.max(0, amount.scale()));
        format.setMaximumFractionDigits(Math.max(0, amount.scale()));
        return format.format(amount);
    }

    private void filing(ReportPdfLayout pdf, String prefix, Filing filing) throws IOException {
        pdf.link(prefix + (filing.corrected() ? "[정정] " : "") + filing.reportName() + " · " + filing.filedAt(),
                safeFilingUrl(filing) ? filing.url() : null);
    }

    private static boolean safeFilingUrl(Filing filing) {
        if (!ReportPdfLayout.safeUrl(filing.url()) || filing.rceptNo() == null || !filing.rceptNo().matches("[0-9]{14}")) return false;
        URI uri = URI.create(filing.url());
        return "https".equalsIgnoreCase(uri.getScheme()) && "dart.fss.or.kr".equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1 && uri.getFragment() == null && "/dsaf001/main.do".equals(uri.getRawPath())
                && ("rcpNo=" + filing.rceptNo()).equals(uri.getRawQuery());
    }

    /** Legacy reports have no structured content: preserve every reading section except collection notes. */
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
                if (title.matches(".*(?:수집\\s*상태|수집\\s*및\\s*출처\\s*참고).*") && skippedLevel == 0) skippedLevel = level;
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
        if (bullet) pdf.bullet(plain(text)); else pdf.paragraph(plain(text));
        var links = MARKDOWN_LINK.matcher(text);
        while (links.find()) if (ReportPdfLayout.safeUrl(links.group(2))) pdf.link("출처 · " + links.group(1), links.group(2));
        var autolinks = AUTOLINK.matcher(text);
        while (autolinks.find()) if (ReportPdfLayout.safeUrl(autolinks.group(1))) pdf.link("기사 원문 · " + autolinks.group(1), autolinks.group(1));
    }

    private String plain(String value) {
        return MARKDOWN_LINK.matcher(value).replaceAll("$1").replaceAll("\\*\\*|__|`", "")
                .replaceAll("<(?:https?://[^>]+)>", "기사 원문 링크");
    }
}
