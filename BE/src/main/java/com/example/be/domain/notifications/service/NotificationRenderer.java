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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 외부 채널에는 보고서 원문 대신 요약과 원문 링크만 렌더링한다. */
@Component
public class NotificationRenderer {

    private static final DateTimeFormatter SUBJECT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        String subject = "[반도체 뉴스] " + report.getGeneratedAt().format(SUBJECT_TIME) + " 보고서";
        StringBuilder html = new StringBuilder("<html><body>")
                .append("<h2>").append(escape(report.getTitle())).append("</h2>")
                .append("<p>보고서에 포함된 핵심 요약입니다.</p><ul>");
        appendEmailItems(html, findings);
        html.append("</ul></body></html>");
        return new RenderedNotification(subject, null, List.of(html.toString()));
    }

    private void appendEmailItems(StringBuilder html, List<Finding> findings) {
        if (findings.isEmpty()) {
            html.append("<li>이 보고서에서 전달할 수 있는 분석 요약이 없습니다.</li>");
            return;
        }
        for (Finding finding : findings) {
            html.append("<li><strong>").append(escape(finding.getArticle().getTitle())).append("</strong>")
                    .append("<p>").append(escape(summary(finding))).append("</p>");
            if (safeUrl(finding.getArticle().getCanonicalUrl())) {
                html.append("<a href=\"").append(attribute(finding.getArticle().getCanonicalUrl()))
                        .append("\">원문 보기</a>");
            }
            html.append("</li>");
        }
    }

    private RenderedNotification renderTelegram(NewsReport report, List<Finding> findings, int maxLength) {
        String header = "<b>" + escape(report.getTitle()) + "</b>";
        if (header.length() > maxLength) {
            header = shortTelegramBlock(header, maxLength);
        }
        List<String> blocks = new ArrayList<>();
        if (findings.isEmpty()) {
            blocks.add("이 보고서에서 전달할 수 있는 분석 요약이 없습니다.");
        } else {
            findings.forEach(finding -> blocks.add(telegramBlock(finding)));
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        for (String block : blocks) {
            String separator = current.length() == 0 ? "" : "\n\n";
            if (current.length() + separator.length() + block.length() <= maxLength) {
                current.append(separator).append(block);
                continue;
            }
            if (!current.isEmpty()) {
                chunks.add(current.toString());
            }
            if (block.length() <= maxLength) {
                current = new StringBuilder(block);
            } else {
                current = new StringBuilder(shortTelegramBlock(block, maxLength));
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return new RenderedNotification(null, "HTML", chunks);
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

    private String telegramBlock(Finding finding) {
        StringBuilder block = new StringBuilder("• <b>")
                .append(escape(finding.getArticle().getTitle())).append("</b>\n")
                .append(escape(summary(finding)));
        if (safeUrl(finding.getArticle().getCanonicalUrl())) {
            block.append("\n<a href=\"").append(attribute(finding.getArticle().getCanonicalUrl()))
                    .append("\">원문</a>");
        }
        return block.toString();
    }

    private String summary(Finding finding) {
        return StringUtils.hasText(finding.getSummary()) ? finding.getSummary().trim() : "요약이 없습니다.";
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String attribute(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value.trim());
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
