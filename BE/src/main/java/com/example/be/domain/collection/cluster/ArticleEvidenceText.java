package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.content.ArticleBodyCleaner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Select one article source without importing a summary's secondary story into a usable body. */
final class ArticleEvidenceText {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final String CREDIT_LABEL = "(?:자료\\s*)?(?:사진|이미지|그래픽|도표|영상)";
    private static final Pattern BRACKETED_CREDIT = Pattern.compile(
            "^(.*?)[\\[(]\\s*" + CREDIT_LABEL + "\\s*(?:제공\\s*)?[=:：][^\\])]*[\\])](.*)$");
    private static final Pattern TRAILING_CREDIT = Pattern.compile(
            "^(.*?)\\s*[/／]\\s*" + CREDIT_LABEL + "\\s*(?:제공\\s*)?[=:：].*$");
    private static final Pattern CREDIT_LINE = Pattern.compile(
            "^(?:[▲△▶▷]\\s*)?" + CREDIT_LABEL + "\\s*(?:제공\\s*)?[=:：].*$");
    private static final Pattern CAPTION_END = Pattern.compile(
            "(?:모습|전경|외관|조감도|개념도|자료사진|기념사진|관련\\s*이미지|사진)\\s*[.!。]?$"
                    + "|^\\[\\s*(?:자료\\s*사진|사진|이미지)\\s*]$");
    private static final Pattern REPORTING_PREDICATE = Pattern.compile(
            "밝혔|발표했|설명했|말했|전했|보도했|개발했|확인했|결정했|체결했|공개했|개최했"
                    + "|분석했|기록했|집계됐|집계되었|추진한다|실시한다|개최한다"
                    + "|[가-힣]{2,}(?:했다|한다|됐다|된다|었다|였다|이다)[.!?](?:\\s|$)");
    private static final Pattern DISPLAY_CONTROL = Pattern.compile(
            "^(?:공유|스크랩|인쇄|로그인|구독|글자\\s*크기|글자\\s*크게|글자\\s*작게|기사\\s*듣기"
                    + "|ADVERTISEMENT|Advertisement|광고)$");

    private ArticleEvidenceText() {
    }

    static String primary(ClusterArticle article) {
        return primary(article.body(), article.summary());
    }

    static String primary(String body, String summary) {
        return select(body, summary).text();
    }

    static Selection select(String body, String summary) {
        String articleBody = clean(body);
        return articleBody.isBlank()
                ? new Selection(clean(summary), true)
                : new Selection(articleBody, false);
    }

    /** Remove identifiable non-article opening lines before applying the evidence budget. */
    static String foreground(String text, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Evidence text limit must not be negative");
        }
        String cleaned = clean(text);
        return cleaned.substring(0, Math.min(cleaned.length(), limit));
    }

    private static String clean(String text) {
        String cleaned = ArticleBodyCleaner.withoutTrailingBoilerplate(text);
        String[] lines = LINE_BREAK.split(cleaned, -1);
        for (int index = 0; index < lines.length; index++) {
            String line = openingLine(lines[index].strip());
            if (line.isBlank()) {
                continue;
            }
            StringBuilder result = new StringBuilder(line);
            for (int remaining = index + 1; remaining < lines.length; remaining++) {
                result.append('\n').append(lines[remaining]);
            }
            // Once an unrecognized line is found, preserve it and everything after it.
            // A short or unfamiliar body is not proof that the summary is more reliable.
            return result.toString().strip();
        }
        return "";
    }

    private static String openingLine(String line) {
        if (line.isBlank() || DISPLAY_CONTROL.matcher(line).matches()) {
            return "";
        }
        Matcher credit = BRACKETED_CREDIT.matcher(line);
        if (credit.matches()) {
            String preceding = credit.group(1).strip();
            String following = credit.group(2).strip();
            // A credited caption can precede a real article in the same extracted line.
            // A reporting sentence before the credit is itself article evidence.
            return REPORTING_PREDICATE.matcher(preceding).find()
                    ? (preceding + " " + following).strip()
                    : openingLine(following);
        }
        Matcher trailing = TRAILING_CREDIT.matcher(line);
        if (trailing.matches()) {
            String preceding = trailing.group(1).strip();
            return REPORTING_PREDICATE.matcher(preceding).find() ? preceding : "";
        }
        if (CREDIT_LINE.matcher(line).matches()
                || (CAPTION_END.matcher(line).find() && !REPORTING_PREDICATE.matcher(line).find())) {
            return "";
        }
        return line;
    }

    record Selection(String text, boolean fromSummary) {
    }
}
