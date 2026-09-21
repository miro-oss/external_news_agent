package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 저장된 보고서의 근거를 채널별 브리핑으로 렌더링한다. 기사 전문은 포함하지 않는다. */
@Component
public class NotificationRenderer {

    private final FindingRepository findingRepository;
    private final TopicRelevancePolicy relevancePolicy;

    public NotificationRenderer(FindingRepository findingRepository, TopicRelevancePolicy relevancePolicy) {
        this.findingRepository = findingRepository;
        this.relevancePolicy = relevancePolicy;
    }

    public RenderedNotification render(NewsReport report, NotificationChannel channel) {
        ReportFindings.Visible visible = ReportFindings.loadVisible(report, findingRepository, relevancePolicy);
        List<Finding> findings = visible.findings();
        ReportReadingContent content = ReportReadingContent.from(report, visible);
        return channel.getChannelType() == ChannelType.EMAIL
                ? renderEmail(report, findings, content)
                : renderTelegram(report, findings, content, channel.getMaxLength());
    }

    public RenderedNotification renderBreakingAlert(String issueTitle,
                                                     String message,
                                                     NotificationChannel channel) {
        if (channel.getChannelType() == ChannelType.EMAIL) {
            String subject = "[속보 후속] " + singleLine(issueTitle);
            String html = "<html><body><h2>속보 후속</h2><p>" + escape(message) + "</p></body></html>";
            return new RenderedNotification(subject, null, List.of(html));
        }
        String body = "<b>속보 후속</b>\n" + escape(message);
        if (body.length() > channel.getMaxLength()) {
            body = shortTelegramBlock(body, channel.getMaxLength());
        }
        return new RenderedNotification(null, "HTML", List.of(body));
    }

    private RenderedNotification renderEmail(NewsReport report, List<Finding> findings, ReportReadingContent content) {
        String subject = "[뉴스 보고서] " + singleLine(normalize(report.getTitle()));
        StringBuilder html = new StringBuilder("<html><body style=\"margin:0;padding:24px;background:#f4f6f8;color:#172331;"
                + "font-family:Arial,sans-serif;line-height:1.7\"><div style=\"max-width:680px;margin:0 auto;"
                + "padding:24px;background:#ffffff\"><p style=\"color:#526578;font-size:12px\">NEWS BRIEFING</p><h2>")
                .append(escape(report.getTitle())).append("</h2><h3>핵심 요약</h3><ul>");
        digest(content, findings, 700).forEach(line -> html.append("<li>").append(escape(line)).append("</li>"));
        html.append("</ul>");
        List<NewsCard> cards = cards(content, findings);
        for (int i = 0; i < cards.size(); i++) {
            NewsCard card = cards.get(i);
            html.append("<div style=\"border-top:1px solid #dce3e9;margin-top:24px;padding-top:16px\">")
                    .append("<p style=\"color:#526578;font-size:12px\">주요 이슈 ").append(i + 1).append("</p><h3>")
                    .append(escape(limit(card.title(), 180))).append("</h3><p><strong>주요 내용</strong><br>")
                    .append(escape(limit(card.summary(), 1200))).append("</p>");
            if (!card.watches().isEmpty()) {
                html.append("<p><strong>후속 확인</strong></p><ul>");
                card.watches().forEach(watch -> html.append("<li>").append(escape(limit(watch, 600))).append("</li>"));
                html.append("</ul>");
            }
            appendCardSources(html, card.urls(), true);
            html.append("</div>");
        }
        if (cards.isEmpty()) appendSources(html, findings, true);
        appendReportLink(html, report, true);
        return new RenderedNotification(subject, null, List.of(html.append("</div></body></html>").toString()));
    }

    private RenderedNotification renderTelegram(NewsReport report, List<Finding> findings,
                                                 ReportReadingContent content, int maxLength) {
        List<NewsCard> cards = cards(content, findings);
        // Fit whole cards and links first. Shorten prose before discarding lower-priority cards.
        for (int count = cards.size(); count > 0; count--) {
            for (int summaryLimit : new int[]{480, 320, 200, 120}) {
                String body = telegramCards(report, cards.subList(0, count), cards.size(), summaryLimit, true, 2);
                if (body.length() <= maxLength) return new RenderedNotification(null, "HTML", List.of(body));
            }
            String body = telegramCards(report, cards.subList(0, count), cards.size(), 120, false, 1);
            if (body.length() <= maxLength) return new RenderedNotification(null, "HTML", List.of(body));
        }
        // Very long source URLs must not replace all issue content with the legacy digest.
        for (int count = cards.size(); count > 0; count--) {
            String body = telegramCards(report, cards.subList(0, count), cards.size(), 120, false, 0);
            if (body.length() <= maxLength) return new RenderedNotification(null, "HTML", List.of(body));
        }
        StringBuilder body = new StringBuilder("<b>").append(escape(limit(normalize(report.getTitle()), 140)))
                .append("</b>\n\n");
        digest(content, findings, 280).forEach(line -> body.append("• ").append(escape(line)).append("\n"));
        appendReportLink(body, report, false);
        appendSources(body, findings, false);
        String rendered = body.toString();
        // A report is one concise message. Never turn a long article list into many notifications.
        if (rendered.length() > maxLength) rendered = shortTelegramBlock(rendered, maxLength);
        return new RenderedNotification(null, "HTML", List.of(rendered));
    }

    private String telegramCards(NewsReport report, List<NewsCard> cards, int total,
                                 int summaryLimit, boolean includeWatches, int sourceLimit) {
        StringBuilder body = new StringBuilder("<b>").append(escape(limit(normalize(report.getTitle()), 100)))
                .append("</b>\n");
        for (int i = 0; i < cards.size(); i++) {
            NewsCard card = cards.get(i);
            body.append("\n<b>").append(i + 1).append(". ").append(escape(limit(card.title(), 90)))
                    .append("</b>\n").append(escape(limit(card.summary(), summaryLimit))).append("\n");
            if (includeWatches) {
                card.watches().forEach(watch -> body.append("확인할 점: ")
                        .append(escape(limit(watch, summaryLimit / 2))).append("\n"));
            }
            appendCardSources(body, card.urls().stream().limit(sourceLimit).toList(), false);
        }
        if (cards.size() < total) body.append("\n길이 제한으로 주요 이슈 ").append(cards.size())
                .append("건을 표시합니다.\n");
        appendReportLink(body, report, false);
        return body.toString();
    }

    private List<NewsCard> cards(ReportReadingContent content, List<Finding> findings) {
        if (content.structuredContent() == null) return List.of();
        Map<Long, Finding> visible = new LinkedHashMap<>();
        findings.forEach(finding -> visible.put(finding.getId(), finding));
        ReportContent structured = content.structuredContent();
        return structured.importantEvents().stream()
                .filter(event -> StringUtils.hasText(event.title()) && StringUtils.hasText(event.summaryKo()))
                .filter(event -> supported(event.sourceFindingIds(), visible))
                .limit(3)
                .map(event -> new NewsCard(prose(event.title()), prose(event.summaryKo()),
                        structured.watchItems().stream()
                                .filter(watch -> supported(watch.sourceFindingIds(), visible))
                                .filter(watch -> watch.sourceFindingIds().stream().anyMatch(event.sourceFindingIds()::contains))
                                .map(watch -> watchText(watch)).filter(StringUtils::hasText).distinct().limit(2).toList(),
                        event.sourceFindingIds().stream().map(visible::get)
                                .map(finding -> finding.getArticle().getCanonicalUrl())
                                .filter(this::safeUrl).distinct().limit(2).toList()))
                .toList();
    }

    private boolean supported(List<Long> ids, Map<Long, Finding> visible) {
        return !ids.isEmpty() && ids.stream().allMatch(visible::containsKey);
    }

    private String watchText(ReportContent.WatchItem watch) {
        String topic = prose(watch.topic());
        String reason = prose(watch.reason());
        if (topic.isEmpty()) return reason;
        return reason.isEmpty() || reason.equals(topic) ? topic : topic + " — " + reason;
    }

    private void appendCardSources(StringBuilder body, List<String> urls, boolean email) {
        if (urls.isEmpty()) return;
        if (email) body.append("<p>");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) body.append(" · ");
            String url = urls.get(i);
            body.append("<a href=\"").append(attribute(url)).append("\">원문 ").append(i + 1)
                    .append(" · ").append(escape(URI.create(url).getHost())).append("</a>");
        }
        body.append(email ? "</p>" : "\n");
    }

    private record NewsCard(String title, String summary, List<String> watches, List<String> urls) {}

    private List<String> digest(ReportReadingContent content, List<Finding> findings, int maxLength) {
        List<String> summary = new ArrayList<>();
        if (content.structuredContent() != null) {
            content.structuredContent().executiveSummary().stream()
                    .filter(StringUtils::hasText).limit(3).forEach(summary::add);
        }
        if (summary.isEmpty() && StringUtils.hasText(content.markdownBody())) {
            boolean inSummary = false;
            for (String line : content.markdownBody().split("\\R")) {
                if (line.matches("^#{1,6}\\s+.*(핵심 요약|오늘의 핵심|요약).*$")) { inSummary = true; continue; }
                if (inSummary && line.startsWith("#")) break;
                if (inSummary && StringUtils.hasText(line)) summary.add(line.replaceFirst("^[-*•]\\s*", ""));
                if (summary.size() == 3) break;
            }
        }
        if (summary.isEmpty()) findings.stream().map(Finding::getSummary)
                .filter(StringUtils::hasText).distinct().limit(3).forEach(summary::add);
        if (summary.isEmpty()) summary.add("이번 수집에서 새롭게 정리할 주요 내용이 없습니다.");
        return summary.stream().map(NotificationRenderer::prose)
                .map(line -> limit(line, maxLength)).limit(3).toList();
    }

    private static String prose(String value) {
        return normalize(value).replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1").replace("**", "");
    }

    @org.springframework.beans.factory.annotation.Value("${news.notifications.public-base-url:}")
    private String publicBaseUrl = "";

    private void appendReportLink(StringBuilder body, NewsReport report, boolean email) {
        if (!safeUrl(publicBaseUrl)) return;
        String url = publicBaseUrl.replaceAll("/+$", "") + "/#/reports?reportId=" + report.getId();
        body.append(email ? "<p>" : "\n").append("<a href=\"").append(attribute(url))
                .append("\">보고서 전체 보기</a>").append(email ? "</p>" : "\n");
    }

    private void appendSources(StringBuilder body, List<Finding> findings, boolean email) {
        List<String> urls = findings.stream().map(f -> f.getArticle().getCanonicalUrl())
                .filter(this::safeUrl).distinct().limit(3).toList();
        if (urls.isEmpty()) return;
        body.append(email ? "<p>참고 원문: " : "\n참고 원문: ");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) body.append(" · ");
            body.append("<a href=\"").append(attribute(urls.get(i))).append("\">자료 ").append(i + 1).append("</a>");
        }
        if (email) body.append("</p>");
    }

    static String normalize(String value) {
        String text = value == null ? "" : value;
        // Decode as text; never parse decoded text as markup. Two passes cover legacy double encoding.
        for (int i = 0; i < 2; i++) {
            String decoded = org.jsoup.parser.Parser.unescapeEntities(text, false);
            if (decoded.equals(text)) break;
            text = decoded;
        }
        return text.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String limit(String value, int max) {
        int count = value.codePointCount(0, value.length());
        return count <= max ? value : value.substring(0, value.offsetByCodePoints(0, max - 1)) + "…";
    }

    /** 정상 설정(3500)에서는 쓰이지 않으며, 비정상적으로 작은 설정에서도 태그를 자르지 않는 안전망이다. */
    private String shortTelegramBlock(String block, int maxLength) {
        String plain = block.replaceAll("<[^>]+>", "").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">");
        if (maxLength <= 0) {
            return "";
        }

        String escaped = escape(plain);
        if (escaped.length() <= maxLength) {
            return escaped;
        }

        if (maxLength == 1) {
            return "…";
        }
        int budget = maxLength - 1;
        StringBuilder shortened = new StringBuilder();
        for (int offset = 0; offset < plain.length();) {
            int codePoint = plain.codePointAt(offset);
            String token = HtmlUtils.htmlEscape(new String(Character.toChars(codePoint)), "UTF-8");
            if (shortened.length() + token.length() > budget) {
                break;
            }
            shortened.append(token);
            offset += Character.charCount(codePoint);
        }
        return shortened.append('…').toString();
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(normalize(value), "UTF-8");
    }

    private String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String attribute(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value.trim(), "UTF-8");
    }

    private boolean safeUrl(String value) {
        if (!StringUtils.hasText(value) || value.chars().anyMatch(character -> Character.isWhitespace(character)
                || character == '<' || character == '>')) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.hasText(uri.getHost());
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
