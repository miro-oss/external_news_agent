package com.example.be.domain.collection.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.util.List;

/**
 * 기사 HTML에서 본문 텍스트만 뽑는다.
 *
 * <p>완전한 readability 구현이 아니다. 메뉴·광고·댓글을 걷어내고 문단이 가장 많이 모인 블록을 고르는 정도다.
 * 매체마다 마크업이 달라 100%는 불가능하고, 실패하면 제목과 링크만 남기면 된다 — 기사를 버리지는 않는다.
 */
public final class ArticleContentExtractor {

    /** 본문일 리 없는 영역. 남겨두면 메뉴와 관련기사 목록이 본문에 섞인다. */
    private static final String NOISE_SELECTOR =
            "script, style, noscript, iframe, form, nav, header, footer, aside, "
                    + "figure figcaption, .advertisement, .ad, .banner, .comment, .comments, .related";

    /** 매체가 본문을 표시할 때 흔히 쓰는 자리. 앞에 있는 것부터 본다. */
    private static final List<String> CONTENT_SELECTORS = List.of(
            "[itemprop=articleBody]",
            "article",
            "#articleBody",
            ".article-body",
            ".article_body",
            ".news-content",
            "#newsct_article",
            "main"
    );

    /**
     * 이보다 짧으면 본문을 못 받은 것으로 본다. 페이월은 보통 "로그인하세요" 한 줄만 준다.
     */
    private static final int MIN_BODY_LENGTH = 200;

    private ArticleContentExtractor() {
    }

    /**
     * 바이트로 받아 <b>Jsoup이 인코딩을 판별하게 한다.</b>
     *
     * <p>문자열로 먼저 디코드하면 charset 헤더가 없는 응답이 ISO-8859-1로 읽혀 한글이 깨진다.
     * 국내 매체 중에는 헤더 없이 {@code <meta charset>}에만 적어 두거나 EUC-KR을 쓰는 곳이 있다.
     * Jsoup은 BOM과 meta 태그를 보고 정하고, 못 찾으면 UTF-8로 본다.
     *
     * @return 본문 텍스트. 쓸 만한 본문을 못 찾으면 null
     */
    public static String extract(byte[] html, String baseUrl) {
        if (html == null || html.length == 0) {
            return null;
        }

        try {
            return extract(Jsoup.parse(new ByteArrayInputStream(html), null, baseUrl == null ? "" : baseUrl));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 인코딩이 이미 정해진 문자열용. 테스트와, 응답을 문자열로 들고 있는 호출부가 쓴다.
     */
    public static String extract(String html, String baseUrl) {
        if (!StringUtils.hasText(html)) {
            return null;
        }

        return extract(Jsoup.parse(html, baseUrl == null ? "" : baseUrl));
    }

    private static String extract(Document document) {
        document.select(NOISE_SELECTOR).remove();

        String body = fromKnownSelectors(document);
        if (body == null) {
            body = fromDensestBlock(document);
        }

        return StringUtils.hasText(body) && body.length() >= MIN_BODY_LENGTH ? body : null;
    }

    private static String fromKnownSelectors(Document document) {
        for (String selector : CONTENT_SELECTORS) {
            Element element = document.selectFirst(selector);
            if (element == null) {
                continue;
            }

            String text = textOf(element);
            if (text.length() >= MIN_BODY_LENGTH) {
                return text;
            }
        }

        return null;
    }

    /**
     * 아는 자리에 없으면 {@code <p>}가 가장 많이 모인 블록을 본문으로 본다. 기사 본문은 대개 문단의 덩어리다.
     */
    private static String fromDensestBlock(Document document) {
        Element best = null;
        int bestLength = 0;

        for (Element candidate : document.select("div, section, td")) {
            Elements paragraphs = candidate.select("> p");
            if (paragraphs.size() < 2) {
                continue;
            }

            int length = paragraphs.stream().mapToInt(p -> p.text().length()).sum();
            if (length > bestLength) {
                best = candidate;
                bestLength = length;
            }
        }

        return best == null ? null : textOf(best);
    }

    /**
     * 문단을 줄바꿈으로 잇는다. Jsoup의 {@code text()}는 전부 한 줄로 붙여서 문장 분할(M4 §3)이 어려워진다.
     */
    private static String textOf(Element element) {
        Elements paragraphs = element.select("p");
        if (paragraphs.isEmpty()) {
            return element.text().strip();
        }

        return paragraphs.stream()
                .map(Element::text)
                .map(String::strip)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("")
                .strip();
    }
}
