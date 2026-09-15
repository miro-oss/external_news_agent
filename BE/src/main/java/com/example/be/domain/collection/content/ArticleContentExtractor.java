package com.example.be.domain.collection.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeVisitor;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    /** 본문 전용 영역. 메타 태그는 표시되는 본문 영역이 아니다. */
    private static final List<String> CONTENT_SELECTORS = List.of(
            "[itemprop=articleBody]:not(meta):not(link)",
            "#articleBody",
            ".article-body",
            ".article_body",
            ".news-content",
            "#newsct_article"
    );

    private static final List<String> GENERIC_CONTENT_SELECTORS = List.of("article", "main");

    /** 일반 컨테이너와 주변 문단 탐색은 충분한 길이가 있어야 본문으로 인정한다. */
    private static final int MIN_BODY_LENGTH = 200;
    private static final String NON_STORY_SELECTOR =
            "h1, h2, h3, h4, h5, h6, [itemprop=headline], .headline, .article-title, .news-title, "
                    + "[itemprop=description], .article-summary, .news-summary, .article-description, "
                    + "figure, figcaption, .caption, .photo-caption, .photo_caption, .image-caption, .image_caption, "
                    + "button, [role=button], .toolbar, .article-tools, .byline, .reporter, .author, "
                    + "[hidden], [aria-hidden=true]";
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern KOREAN_SENTENCE_END = Pattern.compile(
            "[가-힣]+(?:다|요)[.!?。]?[\\\"'”’)]*$");
    private static final Pattern OTHER_SENTENCE_END = Pattern.compile("[.!?。][\\\"'”’)]*$");
    private static final Pattern ACCESS_NOTICE = Pattern.compile(
            "^(?:(?:로그인|회원\\s*가입|구독)(?:을|를)?\\s*(?:후|하면|하시면|하세요|하기|해\\s*주세요|이\\s*필요|하셔야).*"
                    + "|(?:이\\s*)?(?:기사|콘텐츠|내용)(?:는|은).*?(?:회원|구독자).*(?:전용|제공).*"
                    + "|(?:이\\s*)?(?:기사|콘텐츠)(?:는|은)\\s*유료(?:입니다|이다)[.!]?$"
                    + "|(?:이\\s*)?(?:기사|전문|콘텐츠)(?:를|을)?\\s*(?:계속\\s*)?(?:읽으(?:시)?려면|보(?:시)?려면|이용(?:하시)?려면).*?(?:로그인|구독|회원\\s*가입).*"
                    + "|계속\\s*(?:읽으려면|보려면).*?(?:로그인|구독|회원\\s*가입).*"
                    + "|(?:기사\\s*)?(?:전문|전체\\s*기사)(?:을|은|를|는)?\\s*(?:로그인|구독|회원\\s*가입).*"
                    + "|(?:유료\\s*)?(?:구독자|회원)\\s*전용(?:\\s*(?:기사|콘텐츠|서비스|내용))?(?:입니다|이다)?[.!]?"
                    + "|(?:please\\s+)?(?:log\\s*in|sign\\s*in|sign\\s*up|subscribe|register)\\b.*"
                    + "(?:read|access|continue|view|unlock|account|full|article).*"
                    + "|(?:please\\s+)?(?:log\\s*in|sign\\s*in|subscribe|register)(?:\\s+(?:now|here|today))?[.!]?"
                    + "|you\\s+(?:must|need\\s+to)\\s+(?:log\\s*in|sign\\s*in|subscribe|register)\\b.*"
                    + "|this\\s+(?:article|content)\\s+is\\s+(?:only\\s+)?(?:for|available\\s+to)\\s+(?:registered\\s+)?(?:members|subscribers).*"
                    + "|this\\s+(?:article|content)\\s+requires\\s+(?:a\\s+)?(?:subscription|membership).*"
                    + "|(?:a\\s+)?(?:subscription|membership)\\s+is\\s+required\\s+to\\s+(?:read|view|access).*"
                    + "|already\\s+(?:a\\s+)?(?:subscriber|member)\\??\\s*(?:log\\s*in|sign\\s*in).*"
                    + "|to\\s+(?:continue|read|view|access|unlock).*?(?:log\\s*in|sign\\s*in|subscribe|register).*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DISPLAY_CONTROLS = Pattern.compile(
            "^(?:(?:공유|스크랩|인쇄|로그인|구독|글자\\s*크기|글자\\s*크게|글자\\s*작게|기사\\s*듣기"
                    + "|share|print|subscribe|sign\\s*in|advertisement|광고)[\\s|·:/-]*)+$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CAPTION_ONLY = Pattern.compile(
            "^(?:[▲△▶▷]\\s*)?(?:자료\\s*)?(?:사진|이미지|그래픽|도표|영상)\\s*(?:설명|(?:제공\\s*)?[=:：]).*"
                    // '투자를 늘리는 모습이다' 같은 실제 서술 문장을 사진 설명으로 지우지 않는다.
                    + "|모습\\s*[.!。]?$"
                    + "|(?:전경|외관|조감도|개념도|자료사진|기념사진|관련\\s*이미지|사진)(?:이다|입니다)?\\s*[.!。]?$"
                    + "|^\\[\\s*(?:자료\\s*사진|사진|이미지)[^]]*]$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TRAILING_PHOTO_CREDIT = Pattern.compile(
            "\\s*(?:[/／]\\s*|[\\[(])(?:자료\\s*)?(?:사진|이미지|그래픽|도표|영상)\\s*(?:제공\\s*)?[=:：].*$");

    private ArticleContentExtractor() {
    }

    /**
     * 바이트로 받아 <b>Jsoup이 인코딩을 판별하게 한다.</b>
     *
     * <p>문자열로 먼저 디코드하면 charset 헤더가 없는 응답이 ISO-8859-1로 읽혀 한글이 깨진다.
     * 국내 매체 중에는 헤더 없이 {@code <meta charset>}에만 적어 두거나 EUC-KR을 쓰는 곳이 있다.
     *
     * @param charsetName HTTP 헤더가 말한 인코딩. null이면 Jsoup이 BOM·meta를 보고 정하고 못 찾으면 UTF-8
     *
     * @return 본문 텍스트. 쓸 만한 본문을 못 찾으면 null
     */
    public static String extract(byte[] html, String charsetName, String baseUrl) {
        return extract(html, charsetName, baseUrl, null);
    }

    public static String extract(byte[] html, String charsetName, String baseUrl, String articleTitle) {
        if (html == null || html.length == 0) {
            return null;
        }

        try {
            // charsetName이 null이면 Jsoup이 BOM과 meta를 보고 정한다. HTTP 헤더가 말한 값이 있으면 그게 우선이다.
            return extract(Jsoup.parse(
                    new ByteArrayInputStream(html), charsetName, baseUrl == null ? "" : baseUrl), articleTitle);
        } catch (IOException e) {
            return null;
        }
    }

    public static String extract(byte[] html, String baseUrl) {
        return extract(html, null, baseUrl);
    }

    /**
     * 인코딩이 이미 정해진 문자열용. 테스트와, 응답을 문자열로 들고 있는 호출부가 쓴다.
     */
    public static String extract(String html, String baseUrl) {
        return extract(html, baseUrl, null);
    }

    public static String extract(String html, String baseUrl, String articleTitle) {
        if (!StringUtils.hasText(html)) {
            return null;
        }

        return extract(Jsoup.parse(html, baseUrl == null ? "" : baseUrl), articleTitle);
    }

    private static String extract(Document document, String articleTitle) {
        // header를 걷어내기 전에 제목을 확보한다. 제목이나 OG 값을 본문 대신 반환하지는 않는다.
        List<String> titles = new ArrayList<>(document.select("h1, [itemprop=headline]").eachText());
        titles.add(document.title());
        titles.add(document.select("meta[property=og:title]").attr("content"));
        titles.add(document.select("meta[property=og:description]").attr("content"));
        titles.add(document.select("meta[name=description]").attr("content"));
        if (articleTitle != null) {
            titles.add(articleTitle);
        }
        document.select(NOISE_SELECTOR).remove();

        String body = fromKnownSelectors(document, CONTENT_SELECTORS, titles, true);
        // 빈 본문 틀을 받은 페이지에서 주변 키워드·관련기사로 전문 길이를 채우지 않는다.
        if (body == null && document.select(String.join(", ", CONTENT_SELECTORS)).isEmpty()) {
            body = fromKnownSelectors(document, GENERIC_CONTENT_SELECTORS, titles, false);
            if (body == null) {
                body = fromDensestBlock(document);
            }
        }

        return body;
    }

    private static String fromKnownSelectors(Document document, List<String> selectors,
                                            List<String> titles, boolean explicitBody) {
        for (String selector : selectors) {
            for (Element element : document.select(selector)) {
                String text = textOf(element);
                if (ArticleBodyCleaner.withoutTrailingBoilerplate(text).length() >= MIN_BODY_LENGTH) {
                    return text;
                }
                // news-content는 본문뿐 아니라 기사 전체 레이아웃에도 쓰이는 넓은 이름이다.
                if (explicitBody && !selector.equals(".news-content") && hasShortStory(element, titles)) {
                    return text;
                }
            }
        }

        return null;
    }

    /** 검증용 복사본만 정리한다. 저장할 원문의 문장 순서·공백·사진 설명은 바꾸지 않는다. */
    private static boolean hasShortStory(Element body, List<String> titles) {
        if (body.is(NON_STORY_SELECTOR)) {
            return false;
        }
        Element story = body.clone();
        story.select(NON_STORY_SELECTOR).remove();
        String text = storyText(textOf(story));
        if (!hasSentence(text)) {
            return false;
        }
        boolean matchesTitle = titles.stream().filter(StringUtils::hasText)
                .anyMatch(title -> comparable(title).equals(comparable(text)));
        // 실제 본문 p에 완결 문장이 있으면 한 문장 속보의 제목과 전문이 같아도 허용한다.
        // 제목을 복사한 div/span이나 headline 노드만으로는 짧은 본문을 만들지 않는다.
        return !matchesTitle || story.select("p").stream()
                .anyMatch(paragraph -> hasSentence(storyText(textOf(paragraph))));
    }

    private static String storyText(String text) {
        StringBuilder story = new StringBuilder();
        for (String rawLine : ArticleBodyCleaner.withoutTrailingBoilerplate(text).split("\\R")) {
            String line = TRAILING_PHOTO_CREDIT.matcher(rawLine.strip()).replaceFirst("").strip();
            if (!line.isBlank() && !ACCESS_NOTICE.matcher(line).matches()
                    && !DISPLAY_CONTROLS.matcher(line).matches() && !CAPTION_ONLY.matcher(line).find()) {
                story.append(line).append('\n');
            }
        }
        return story.toString().strip();
    }

    private static boolean hasSentence(String text) {
        for (String line : text.split("\\R")) {
            if (WORD.matcher(line).results().limit(2).count() < 2) {
                continue;
            }
            boolean korean = line.codePoints().anyMatch(value -> value >= '가' && value <= '힣');
            if ((korean ? KOREAN_SENTENCE_END : OTHER_SENTENCE_END).matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static String comparable(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * 아는 자리에 없으면 직접 자식 문단에서 매체 푸터를 제외한 본문이 가장 긴 블록을 고른다.
     */
    private static String fromDensestBlock(Document document) {
        Element best = null;
        int bestLength = 0;

        for (Element candidate : document.select("div, section, td")) {
            Elements paragraphs = candidate.select("> p");
            if (paragraphs.size() < 2) {
                continue;
            }

            // 전체 본문으로 수용 여부를 판단하고, 직접 문단으로만 블록 간 길이를 비교한다.
            if (ArticleBodyCleaner.withoutTrailingBoilerplate(textOf(candidate)).length() < MIN_BODY_LENGTH) {
                continue;
            }

            int length = ArticleBodyCleaner.withoutTrailingBoilerplate(textOf(paragraphs)).length();
            if (length > bestLength) {
                best = candidate;
                bestLength = length;
            }
        }

        return best == null ? null : textOf(best);
    }

    /**
     * DOM 순서대로 텍스트를 읽고 문단·블록과 {@code br}의 경계를 보존한다.
     * 사진 설명만 {@code p}인 기사도 있어 자손 문단만 읽으면 실제 본문을 잃는다.
     */
    private static String textOf(Element element) {
        BodyTextVisitor visitor = new BodyTextVisitor();
        element.traverse(visitor);
        return visitor.text.toString().strip();
    }

    private static final class BodyTextVisitor implements NodeVisitor {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode textNode) {
                // Jsoup의 공백 정규화는 HTML 들여쓰기를 접되 인라인 요소 사이의 실제 공백은 남긴다.
                String value = textNode.text();
                if (text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1))) {
                    value = value.stripLeading();
                }
                text.append(value);
            } else if (node instanceof Element child) {
                if (child.normalName().equals("br")) {
                    lineBreak(1, true);
                } else if (child.isBlock()) {
                    lineBreak(2, false);
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (node instanceof Element child && child.isBlock()) {
                lineBreak(2, false);
            }
        }

        private void lineBreak(int count, boolean additive) {
            while (!text.isEmpty() && text.charAt(text.length() - 1) == ' ') {
                text.setLength(text.length() - 1);
            }
            if (text.isEmpty()) {
                return;
            }
            int existing = 0;
            for (int index = text.length() - 1; index >= 0 && text.charAt(index) == '\n'; index--) {
                existing++;
            }
            int target = additive ? Math.min(2, existing + count) : count;
            while (existing++ < target) {
                text.append('\n');
            }
        }
    }

    private static String textOf(Elements paragraphs) {
        return paragraphs.stream()
                .map(Element::text)
                .map(String::strip)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("")
                .strip();
    }
}
