package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.ReportFindings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/** 외부 채널에는 보고서 원문 대신 요약과 원문 링크만 렌더링한다. */
@Component
public class NotificationRenderer {

    private final FindingRepository findingRepository;

    public NotificationRenderer(FindingRepository findingRepository) {
        this.findingRepository = findingRepository;
    }

    public RenderedNotification render(NewsReport report, NotificationChannel channel) {
        List<Finding> findings = ReportFindings.load(report, findingRepository);
        return channel.getChannelType() == ChannelType.EMAIL
                ? renderEmail(report, findings)
                : renderTelegram(report, findings, channel.getMaxLength());
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

    private RenderedNotification renderEmail(NewsReport report, List<Finding> findings) {
        String subject = "[뉴스 보고서] " + singleLine(normalize(report.getTitle()));
        StringBuilder html = new StringBuilder("<html><body><h2>")
                .append(escape(report.getTitle())).append("</h2><h3>핵심 요약</h3><ul>");
        digest(report, findings).forEach(line -> html.append("<li>").append(escape(line)).append("</li>"));
        html.append("</ul>");
        appendReportLink(html, report, true);
        appendSources(html, findings, true);
        return new RenderedNotification(subject, null, List.of(html.append("</body></html>").toString()));
    }

    private RenderedNotification renderTelegram(NewsReport report, List<Finding> findings, int maxLength) {
        StringBuilder body = new StringBuilder("<b>").append(escape(limit(normalize(report.getTitle()), 140)))
                .append("</b>\n\n");
        digest(report, findings).forEach(line -> body.append("• ").append(escape(line)).append("\n"));
        appendReportLink(body, report, false);
        appendSources(body, findings, false);
        String rendered = body.toString();
        // A report is one concise message. Never turn a long article list into many notifications.
        if (rendered.length() > maxLength) rendered = shortTelegramBlock(rendered, maxLength);
        return new RenderedNotification(null, "HTML", List.of(rendered));
    }

    private List<String> digest(NewsReport report, List<Finding> findings) {
        List<String> summary = new ArrayList<>();
        if (report.getStructuredContent() != null) {
            report.getStructuredContent().executiveSummary().stream()
                    .filter(StringUtils::hasText).limit(3).forEach(summary::add);
        }
        if (summary.isEmpty() && StringUtils.hasText(report.getMarkdownBody())) {
            boolean inSummary = false;
            for (String line : report.getMarkdownBody().split("\\R")) {
                if (line.matches("^#{1,6}\\s+.*(핵심 요약|오늘의 핵심|요약).*$")) { inSummary = true; continue; }
                if (inSummary && line.startsWith("#")) break;
                if (inSummary && StringUtils.hasText(line)) summary.add(line.replaceFirst("^[-*•]\\s*", ""));
                if (summary.size() == 3) break;
            }
        }
        if (summary.isEmpty()) findings.stream().map(Finding::getSummary)
                .filter(StringUtils::hasText).distinct().limit(3).forEach(summary::add);
        if (summary.isEmpty()) summary.add("이번 수집에서 새롭게 정리할 주요 내용이 없습니다.");
        return summary.stream().map(NotificationRenderer::normalize)
                .map(line -> line.replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1").replace("**", ""))
                .map(line -> limit(line, 280)).limit(3).toList();
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
        body.append(email ? "<p>대표 원문: " : "\n대표 원문: ");
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
            String token = escape(new String(Character.toChars(codePoint)));
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
