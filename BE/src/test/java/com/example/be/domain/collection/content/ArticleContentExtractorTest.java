package com.example.be.domain.collection.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleContentExtractorTest {

    /** 넓은 article/main과 문단 밀집 탐색의 본문 길이 기준을 넘기는 일반 기사. */
    private static final String PARAGRAPH =
            ("삼성전자가 HBM4 양산 일정을 앞당기기로 했다. 업계에 따르면 이번 결정은 고객사 요구를 반영한 것이다. ").repeat(3);

    private static final String PUBLISHER_NOTICE =
            "대표이사 : 합성 담당자 " + "테스트용 매체의 운영 안내입니다. ".repeat(8);
    private static final String COPYRIGHT_NOTICE =
            "무단 전재 및 재배포 금지. " + "테스트용 매체의 저작권 안내입니다. ".repeat(4);

    @ParameterizedTest
    @ValueSource(strings = {"role='navigation'", "role='menu'", "role='menubar'", "id='gnb'", "class='lnb'"})
    void removesNavigationContainersInsideTheArticle(String attributes) {
        String html = "<div id='articleBody'><div " + attributes + ">"
                + "<a href='/latest'>오늘의 새 소식</a><a href='/economy'>경제 기사 모아보기</a>"
                + "</div><p>" + PARAGRAPH + "</p></div>";

        assertEquals(PARAGRAPH.strip(), ArticleContentExtractor.extract(html, "https://example.com/story"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<div id='articleBody'>", "<article>", "<main>", "<div>"})
    void removesPlainLeadingMenuFromEveryExtractionPath(String opening) {
        String closing = opening.contains("articleBody") || opening.equals("<div>") ? "</div>"
                : opening.equals("<article>") ? "</article>" : "</main>";
        String html = opening + "<p>최신뉴스</p><p>정치</p><p>경제</p><p>사회</p>"
                + "<p>생활문화</p><p>스포츠</p><p>국제</p><p>날씨</p>"
                + "<p>" + PARAGRAPH + "</p><p>" + PARAGRAPH + "</p>" + closing;

        assertEquals(String.join("\n\n", PARAGRAPH.strip(), PARAGRAPH.strip()),
                ArticleContentExtractor.extract(html, "https://example.com/story"));
    }

    @Test
    void menuCannotPadAnOtherwiseTooShortGenericBody() {
        String menu = "<p>최신뉴스</p><p>정치</p><p>경제</p><p>사회</p>".repeat(20);

        assertNull(ArticleContentExtractor.extract("<article>" + menu
                + "<p>새 공장을 설립했다.</p></article>", "https://example.com/brief"));
        assertNull(ArticleContentExtractor.extract("<div id='articleBody'>" + menu + "</div>",
                "https://example.com/menu"));
    }

    @Test
    void takesOnlyTheObservedSbsAmpBodyBeforeTheGenericMainContainer() {
        String html = "<main><div class='article_content_end_middle'><div class='acem_text'>"
                + PARAGRAPH + "<br>" + PARAGRAPH + "</div></div>"
                + "<div class='reporter_article_list'><p>" + "다른 기사의 본문이다. ".repeat(20) + "</p></div></main>";

        String body = ArticleContentExtractor.extract(html, "https://publisher.example/amp/article/123456");

        assertTrue(body.contains("HBM4 양산 일정"));
        assertFalse(body.contains("다른 기사"));
    }

    @Test
    void takesArticleBody() {
        String html = """
                <html><body>
                  <nav>메뉴 메뉴 메뉴</nav>
                  <article>
                    <p>%s</p>
                    <p>%s</p>
                  </article>
                  <footer>회사 소개</footer>
                </body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertTrue(body.contains("HBM4 양산 일정"));
        assertFalse(body.contains("메뉴"));
        assertFalse(body.contains("회사 소개"));
    }

    @Test
    void extractsTheNestedNewsBodyWithBreaksWithoutTakingItsLayoutOrRelatedStories() {
        String html = """
                <html><body><div class='con_left'>
                  <div class='detail_box'><div class='news_contents'>
                    <div class='vodPlayer'>영상 안내</div>
                    <div class='con_sub'>한국은행이 <strong>기준금리</strong>를 동결했다.<br><br>시장 상황을 계속 점검한다.</div>
                  </div></div>
                  <div class='list_type_01b'><p>관련 기사 제목</p><p>다른 소식</p></div>
                </div><div class='con_sub'>사이트 안내 문구다.</div></body></html>
                """;

        assertEquals("한국은행이 기준금리를 동결했다.\n\n시장 상황을 계속 점검한다.",
                ArticleContentExtractor.extract(html, "https://publisher.example/story"));
    }

    @Test
    void doesNotTreatEitherNestedNewsBodyClassAloneAsAnArticle() {
        String text = "한국은행이 기준금리를 동결했다.";
        for (String className : new String[] {"news_contents", "con_sub"}) {
            assertNull(ArticleContentExtractor.extract("<div class='" + className + "'>" + text + "</div>",
                    "https://publisher.example/story"));
        }
    }

    @Test
    void anEmptyNestedNewsBodyCannotBeFilledFromRelatedArticleParagraphs() {
        String html = "<div class='news_contents'><div class='con_sub'></div></div>"
                + "<div><p>" + PARAGRAPH + "</p><p>" + PARAGRAPH + "</p></div>";

        assertNull(ArticleContentExtractor.extract(html, "https://publisher.example/story"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"class='article-body'", "id='ctl00_ContentPlaceHolder1_WebNewsView_ltContentDiv' class='rns_text'"})
    void preservesADedicatedArticleBodyInsideASelfPostingWebFormsPage(String attributes) {
        String html = "<meta property='og:type' content='article'>"
                + "<form method='post' action='./story.aspx?id=7'>"
                + "<input type='hidden' name='__VIEWSTATE' value='synthetic-state'>"
                + "<nav>사이트 메뉴</nav><div " + attributes + ">"
                + "한국은행이 기준금리를 동결했다.<br>시장 상황을 계속 점검한다."
                + "<button>댓글 쓰기</button><textarea>댓글 입력 내용</textarea></div>"
                + "<section><p>" + PARAGRAPH + "</p><p>관련 기사 내용이다.</p></section></form>";

        assertEquals("한국은행이 기준금리를 동결했다.\n시장 상황을 계속 점검한다.",
                ArticleContentExtractor.extract(html, "https://publisher.example/story.aspx?id=7"));
        assertEquals("한국은행이 기준금리를 동결했다.\n시장 상황을 계속 점검한다.",
                ArticleContentExtractor.extract(html.replace("action='./story.aspx?id=7'", "action=''"),
                        "https://publisher.example/story.aspx?id=7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"search", "login", "comment"})
    void webFormsControlsAndUnmarkedLongTextCannotBecomeAnArticle(String purpose) {
        String html = "<meta property='og:type' content='article'><form method='post' action=''>"
                + "<input type='hidden' name='__VIEWSTATE' value='synthetic-state'>"
                + "<div class='" + purpose + "'><p>" + PARAGRAPH + "</p><p>" + PARAGRAPH + "</p></div>"
                + "<textarea>" + PARAGRAPH + "</textarea></form>";

        assertNull(ArticleContentExtractor.extract(html, "https://publisher.example/story.aspx?id=7"));
    }

    @Test
    void anEmptyBodyInsideWebFormsCannotBorrowNeighboringFormParagraphs() {
        String html = "<meta property='og:type' content='article'><form method='post' action=''>"
                + "<input type='hidden' name='__VIEWSTATE' value='synthetic-state'>"
                + "<div class='article-body'></div><div><p>" + PARAGRAPH + "</p><p>" + PARAGRAPH + "</p></div></form>";

        assertNull(ArticleContentExtractor.extract(html, "https://publisher.example/story.aspx?id=7"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aside", "div class='related'", "div class='comments'", "div hidden",
            "div aria-hidden='true'", "div class='caption'", "div class='byline'"})
    void webFormsBodyCannotEscapeAnExcludedAncestorWhenPreserved(String wrapper) {
        String closingTag = wrapper.split(" ")[0];
        String html = "<meta property='og:type' content='article'><form method='post' action=''>"
                + "<input type='hidden' name='__VIEWSTATE' value='synthetic-state'><" + wrapper + ">"
                + "<div class='article-body'><p>다른 지역에서 별도의 행사가 열렸다. 참가 기업들은 독립된 협약을 체결했다.</p>"
                + "</div></" + closingTag + "></form>";
        String url = "https://publisher.example/story.aspx?id=7";

        assertNull(ArticleContentExtractor.extract(html, url));

        String withCurrentArticle = html.replace("</form>",
                "<div class='article-body'><p>한국은행이 기준금리를 동결했다.</p></div></form>");
        assertEquals("한국은행이 기준금리를 동결했다.", ArticleContentExtractor.extract(withCurrentArticle, url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"class='article-body related'", "class='article-body comments'",
            "class='article-body' hidden", "class='article-body' aria-hidden='true'",
            "class='article-body caption'", "class='article-body byline'"})
    void webFormsPreservationAlsoRejectsAnExcludedBodyElementItself(String attributes) {
        String html = "<meta property='og:type' content='article'><form method='post' action=''>"
                + "<input type='hidden' name='__VIEWSTATE' value='synthetic-state'><div " + attributes + ">"
                + "<p>다른 지역에서 별도의 행사가 열렸다. 참가 기업들은 독립된 협약을 체결했다.</p></div></form>";

        assertNull(ArticleContentExtractor.extract(html, "https://publisher.example/story.aspx?id=7"));
    }

    @Test
    void doesNotPreserveInteractiveFormsOrArticleBodiesRequiringLogin() {
        String start = "<meta property='og:type' content='article'><form method='post' action=''>"
                + "<input type='hidden' name='__VIEWSTATE' value='synthetic-state'>";
        String body = "<div class='article-body'><p>" + PARAGRAPH + "</p></div></form>";
        String url = "https://publisher.example/story.aspx?id=7";

        assertNull(ArticleContentExtractor.extract(start + "<input type='password'>" + body, url));
        assertNull(ArticleContentExtractor.extract((start + body).replace("action=''", "action='/login'"), url));
        assertNull(ArticleContentExtractor.extract((start + body).replace("action=''", "action='https://other.example/'"), url));
        assertNull(ArticleContentExtractor.extract((start + body).replace("type='hidden'", "type='text'"), url));
        assertNull(ArticleContentExtractor.extract((start + body).replace("content='article'", "content='website'"), url));
        assertNull(ArticleContentExtractor.extract((start + body).replace("<p>", "<p>기사 전문은 로그인 후 확인할 수 있습니다.</p><p>"), url));
    }

    @Test
    void extractsTheMarkedDirectTextBodyWithoutNeighboringNavigation() {
        String html = "<div id='joinskmbox'>한국은행이 기준금리를 동결했다.<br><br>시장 상황을 계속 점검한다.</div>"
                + "<div><p>" + PARAGRAPH + "</p><p>관련 기사 내용이다.</p></div>";

        assertEquals("한국은행이 기준금리를 동결했다.\n\n시장 상황을 계속 점검한다.",
                ArticleContentExtractor.extract(html, "https://publisher.example/article.php?aid=1"));
    }

    @Test
    void rejectsAnEmptyCaptionOrLoginOnlyMarkedDirectTextBody() {
        for (String value : new String[] {"", "사진 설명입니다.", "기사 전문은 로그인 후 확인할 수 있습니다."}) {
            String html = "<div id='joinskmbox'>" + value + "</div>"
                    + "<div><p>" + PARAGRAPH + "</p><p>" + PARAGRAPH + "</p></div>";
            assertNull(ArticleContentExtractor.extract(html, "https://publisher.example/article.php?aid=1"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"itemprop='articleBody'", "id='articleBody'", "class='article-body'",
            "class='article_body'", "id='newsct_article'"})
    void acceptsACompleteShortBulletinInAnExplicitBody(String attributes) {
        String bulletin = "한국은행이 기준금리를 연 2.50%로 동결했다.";
        String html = "<html><body><div %s><p>%s</p></div></body></html>".formatted(attributes, bulletin);

        assertEquals(bulletin, ArticleContentExtractor.extract(html, "https://example.com/brief"));
    }

    @Test
    void acceptsAShortEnglishArticleAndPreservesItsText() {
        String bulletin = "The central bank held its policy rate at 2.5 percent.";
        String html = "<div itemprop='articleBody'><p>%s</p></div>".formatted(bulletin);

        assertEquals(bulletin, ArticleContentExtractor.extract(html, "https://example.com/brief"));
    }

    @Test
    void acceptsDirectShortBodyTextAndPreservesInlineAndParagraphBoundaries() {
        String html = """
                <div id='articleBody'>한국은행이 <strong>기준금리</strong>를 동결했다.<br><br>시장 상황을 계속 점검한다.</div>
                """;

        assertEquals("한국은행이 기준금리를 동결했다.\n\n시장 상황을 계속 점검한다.",
                ArticleContentExtractor.extract(html, "https://example.com/brief"));
    }

    @Test
    void allowsARealBodyParagraphEvenWhenItExactlyRepeatsTheTitle() {
        String bulletin = "한국은행이 기준금리를 동결했다.";
        String html = "<h1>%s</h1><div itemprop='articleBody'><p>%s</p></div>".formatted(bulletin, bulletin);

        assertEquals(bulletin, ArticleContentExtractor.extract(html, "https://example.com/brief", bulletin));
    }

    @Test
    void rejectsACopiedTitleWithoutABodyParagraph() {
        String title = "한국은행이 기준금리를 동결했다.";
        String html = "<div itemprop='articleBody'><span>%s</span></div>".formatted(title);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/headline", title));
    }

    @ParameterizedTest
    @MethodSource("titleFormattingVariants")
    void rejectsCopiedTitleWhenOnlyCaseOrTerminalPunctuationDiffers(String title, String copiedTitle) {
        String html = "<div itemprop='articleBody'><span>%s</span></div>".formatted(copiedTitle);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/headline", title));
    }

    @ParameterizedTest
    @MethodSource("titleFormattingVariants")
    void retainsRealBodyParagraphWhenItsTitleDiffersOnlyInFormatting(String title, String paragraph) {
        String html = "<div itemprop='articleBody'><p>%s</p></div>".formatted(paragraph);

        assertEquals(paragraph, ArticleContentExtractor.extract(html, "https://example.com/brief", title));
    }

    private static Stream<Arguments> titleFormattingVariants() {
        return Stream.of(
                Arguments.of("The central bank held interest rates steady",
                        "The central bank held interest rates steady."),
                Arguments.of("THE CENTRAL BANK HELD INTEREST RATES STEADY.",
                        "The central bank held interest rates steady."),
                Arguments.of("The central bank held interest rates steady!",
                        "the central bank held interest rates steady."),
                Arguments.of("“Bank holds rates”", "“Bank holds rates.”"),
                Arguments.of("\"Bank holds rates\"", "\"Bank holds rates.\""),
                Arguments.of("한국은행이 기준금리를 동결했다", "한국은행이 기준금리를 동결했다."),
                Arguments.of("한국은행이 기준금리를 동결했다!", "한국은행이 기준금리를 동결했다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"h1", "div itemprop='headline'", "p class='article-title'", "p itemprop='description'"})
    void rejectsHeadlineOrSummaryMarkupInsideAnExplicitBody(String tag) {
        String tagName = tag.split(" ")[0];
        String html = "<div itemprop='articleBody'><%s>한국은행이 기준금리를 동결했다.</%s></div>"
                .formatted(tag, tagName);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/headline"));
    }

    @Test
    void doesNotPromoteOgDescriptionIntoFullTextOrAcceptItsCopyWithoutAParagraph() {
        String description = "한국은행이 기준금리를 동결했다.";
        String metadata = "<head><meta property='og:description' content='%s'></head>".formatted(description);

        assertNull(ArticleContentExtractor.extract("<html>" + metadata + "<body></body></html>", "https://example.com/og"));
        assertNull(ArticleContentExtractor.extract("<html>" + metadata
                + "<body><div id='articleBody'><span>" + description + "</span></div></body></html>",
                "https://example.com/og"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"article", "main", "div class='news-content'", "div class='unknown-wrapper'"})
    void keepsTheLengthRequirementForBroadContainers(String tag) {
        String tagName = tag.split(" ")[0];
        String html = "<%s><p>한국은행이 기준금리를 동결했다.</p><p>추가 상황을 점검한다.</p></%s>"
                .formatted(tag, tagName);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/generic"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"로그인 후 이용해 주세요.", "로그인을 해주세요.", "로그인이 필요합니다.",
            "구독하시면 기사 전문을 읽을 수 있습니다.", "이 콘텐츠는 구독자 전용입니다.",
            "기사를 계속 읽으려면 로그인해 주세요.", "이 기사를 보시려면 로그인해 주세요.",
            "기사 전문은 로그인 후 확인할 수 있습니다.", "유료 구독자 전용 기사입니다.", "이 기사는 유료입니다.",
            "Sign in to continue reading this article.", "Subscribe to read the full article.",
            "You must log in to read this article.", "This article is for subscribers only.",
            "This article requires a subscription.", "A subscription is required to read this article.",
            "Please sign in.", "Subscribe now.", "Already a subscriber? Sign in.", "To continue reading, please log in.",
            "사진 설명입니다.", "연구소 건물 전경이다.", "연구소 건물 전경. /사진=자료", "공유 | 구독 | 로그인"})
    void rejectsAccessInstructionsCaptionsAndControlsInAnExplicitBody(String text) {
        String html = "<div id='articleBody'><p>%s</p></div>".formatted(text);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/non-story"));
    }

    @ParameterizedTest
    @MethodSource("englishCaptionExamples")
    void rejectsAnEnglishCaptionAsTheOnlyTextInAnExplicitBody(String caption) {
        String html = "<div id='articleBody'><p>%s</p></div>".formatted(caption);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/photo"));
    }

    @ParameterizedTest
    @MethodSource("englishCaptionExamples")
    void captionParagraphDoesNotQualifyACopiedTitleForTheRealParagraphException(String caption) {
        String html = "<div id='articleBody'><span>The central bank held interest rates steady.</span>"
                + "<p>" + caption + "</p></div>";

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/headline",
                "THE CENTRAL BANK HELD INTEREST RATES STEADY"));
    }

    @ParameterizedTest
    @MethodSource("englishCaptionExamples")
    void preservesEnglishCaptionTextWhenARealShortStoryIsPresent(String caption) {
        String bulletin = "The chipmaker opened a new semiconductor plant on Monday.";
        String html = "<div id='articleBody'><p>%s</p><p>%s</p></div>".formatted(caption, bulletin);

        assertEquals(caption + "\n\n" + bulletin,
                ArticleContentExtractor.extract(html, "https://example.com/factory"));
    }

    private static Stream<String> englishCaptionExamples() {
        return Stream.of(
                "Photo: A semiconductor plant stands beside the river.",
                "Image: Engineers examine a newly built chip factory.",
                "Caption: The central bank building is shown on Monday.",
                "Photo by Jane Doe.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Photo sharing companies reported higher revenue this quarter.",
            "Image sensors generated higher revenue for the chipmaker.",
            "Caption software now supports twelve additional languages.",
            "The company published photos of its new semiconductor plant."})
    void retainsOrdinaryReportingAboutPhotosImagesAndCaptions(String body) {
        String html = "<div id='articleBody'><p>%s</p></div>".formatted(body);

        assertEquals(body, ArticleContentExtractor.extract(html, "https://example.com/technology"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"figure", "figcaption", "p class='photo-caption'", "div class='toolbar'",
            "button", "div hidden", "div aria-hidden='true'"})
    void doesNotUseNonStoryDomAsEvidenceEvenWhenItContainsASentence(String tag) {
        String tagName = tag.split(" ")[0];
        String html = "<div id='articleBody'><%s>합성 연구소가 새 장비를 공개했다.</%s></div>".formatted(tag, tagName);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/non-story"));
    }

    @Test
    void acceptsActualReportingAboutLoginAndSubscriptions() {
        String body = "구독 서비스가 로그인 장애로 중단됐다고 회사는 밝혔다.";

        assertEquals(body, ArticleContentExtractor.extract(
                "<div id='articleBody'><p>" + body + "</p></div>", "https://example.com/incident"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"삼성전자는 HBM 공급 부족으로 생산설비 투자를 늘리는 모습이다.",
            "삼성전자는 HBM 공급 부족으로 생산설비 투자를 늘리는 모습입니다."})
    void keepsShortReportingThatEndsWithADescriptionOfTheSituation(String body) {
        assertEquals(body, ArticleContentExtractor.extract(
                "<div id='articleBody'><p>" + body + "</p></div>", "https://example.com/investment"));
    }

    @Test
    void preservesACaptionAndFooterAlongsideARealShortStory() {
        String bulletin = "합성 연구소가 새 장비를 공개했다.";
        String caption = "합성 장비 사진 설명";
        String html = "<div id='articleBody'><p class='photo-caption'>%s</p><p>%s</p><p>%s</p><p>%s</p></div>"
                .formatted(caption, bulletin, PUBLISHER_NOTICE, COPYRIGHT_NOTICE);

        assertEquals(String.join("\n\n", caption, bulletin, PUBLISHER_NOTICE.strip(), COPYRIGHT_NOTICE.strip()),
                ArticleContentExtractor.extract(html, "https://example.com/brief"));
    }

    @Test
    void rejectsEmptyExplicitBodyInsteadOfUsingSurroundingLinks() {
        String links = "<a href='/other'>관련 없는 행사와 기업의 다른 기사 목록입니다.</a>".repeat(15);
        String html = """
                <html><body><main><article>
                  <div itemprop="articleBody"><figure><figcaption>사진 설명</figcaption></figure><p></p></div>
                  <div><p>관련 키워드</p><p>%s</p></div>
                </article></main></body></html>
                """.formatted(links);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/empty"));
    }

    @Test
    void prefersExplicitBodyOverEarlierBroadArticle() {
        String html = """
                <html><body>
                  <article><p>%s</p></article>
                  <div id="articleBody"><p>%s</p></div>
                </body></html>
                """.formatted("주변 추천 기사 소식입니다. ".repeat(30), PARAGRAPH.repeat(2));

        assertEquals(PARAGRAPH.repeat(2).strip(), ArticleContentExtractor.extract(html, "https://example.com/explicit"));
    }

    @Test
    void triesEveryExplicitBodyBeforeRejectingEmptyPlaceholder() {
        String html = """
                <html><body>
                  <div itemprop="articleBody"><p></p></div>
                  <div itemprop="articleBody"><p>%s</p></div>
                </body></html>
                """.formatted(PARAGRAPH.repeat(2));

        assertEquals(PARAGRAPH.repeat(2).strip(), ArticleContentExtractor.extract(html, "https://example.com/second"));
    }

    @Test
    void doesNotTreatMetadataAsAnEmptyVisibleBody() {
        String html = """
                <html><head><meta itemprop="articleBody" content="본문 메타 정보"></head>
                <body><article><p>%s</p></article></body></html>
                """.formatted(PARAGRAPH.repeat(2));

        assertEquals(PARAGRAPH.repeat(2).strip(), ArticleContentExtractor.extract(html, "https://example.com/metadata"));
    }

    @Test
    void readsDirectArticleTextInsteadOfFallingBackToUnrelatedHeadlines() {
        String firstParagraph = "합성 연구소가 새 관측 장비를 공개하고 다음 달 실험 일정을 안내했다. ".repeat(4).strip();
        String secondParagraph = "실험팀은 측정 정밀도를 확인한 뒤 지역 연구 기관에 장비를 제공할 예정이다. ".repeat(4).strip();
        String unrelatedHeadline = "관련 없는 다른 지역의 행사 소식을 모은 공통 헤드라인이다. ".repeat(5);
        String html = """
                <html><body>
                  <div itemprop="articleBody"><article>
                    <div><p>합성 장비 사진 설명</p></div>
                    %s<br><br>
                    %s
                  </article></div>
                  <div><p>오늘의 헤드라인</p><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(firstParagraph, secondParagraph, unrelatedHeadline, unrelatedHeadline);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", "합성 장비 사진 설명", firstParagraph, secondParagraph), body);
        assertFalse(body.contains("헤드라인"));
    }

    @Test
    void preservesMixedTextOrderInlineSpacingAndLineBreaks() {
        String html = """
                <html><body><article>
                  합성 연구<em>소</em>가 <strong>새 장비</strong>를 공개했다.<br>
                  <span>첫</span><span>실험</span> 결과를 발표했다.
                  <p>%s</p>
                  <section>다음 <a href="/plan">실험 일정</a>을 안내했다.<br><br>후속 관측을 준비한다.</section>
                  마지막 안내를 덧붙였다.
                </article></body></html>
                """.formatted(PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n",
                "합성 연구소가 새 장비를 공개했다.\n첫실험 결과를 발표했다.",
                PARAGRAPH.strip(), "다음 실험 일정을 안내했다.", "후속 관측을 준비한다.",
                "마지막 안내를 덧붙였다."), body);
    }

    /**
     * 스크립트를 남기면 본문에 자바스크립트가 섞여 들어간다. M4의 문장 분할이 그대로 오염된다.
     */
    @Test
    void dropsScriptAndStyle() {
        String html = """
                <html><body><article>
                  <script>var tracking = "안 보여야 한다";</script>
                  <style>.a { color: red; }</style>
                  <p>%s</p><p>%s</p>
                </article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertFalse(body.contains("tracking"));
        assertFalse(body.contains("color"));
    }

    /**
     * 아는 자리에 없으면 문단이 가장 많이 모인 블록을 본문으로 본다.
     */
    @Test
    void fallsBackToDensestParagraphBlock() {
        String html = """
                <html><body>
                  <div class="side"><p>짧은 홍보</p></div>
                  <div class="unknown-wrapper"><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertTrue(body.contains("HBM4 양산 일정"));
        assertFalse(body.contains("짧은 홍보"));
    }

    @Test
    void rejectsPublisherFooterOnlyInDensestParagraphBlock() {
        String html = """
                <html><body><div><p>%s</p><p>%s</p></div></body></html>
                """.formatted(PUBLISHER_NOTICE, COPYRIGHT_NOTICE);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/1"));
    }

    @Test
    void skipsPublisherFooterForLaterKnownArticleSelector() {
        String html = """
                <html><body>
                  <article><p>%s</p><p>%s</p></article>
                  <div class="article-body"><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(PUBLISHER_NOTICE, COPYRIGHT_NOTICE, PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", PARAGRAPH.strip(), PARAGRAPH.strip()), body);
    }

    @Test
    void selectsUsableArticleInsteadOfLongerPublisherFooterBlock() {
        String html = """
                <html><body>
                  <div><p>%s</p><p>%s</p></div>
                  <div><p>%s</p><p>%s</p></div>
                </body></html>
                """.formatted(PUBLISHER_NOTICE.repeat(3), COPYRIGHT_NOTICE.repeat(3), PARAGRAPH, PARAGRAPH);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", PARAGRAPH.strip(), PARAGRAPH.strip()), body);
    }

    @Test
    void selectsNestedArticleWithoutCountingItsTextForOuterWrapper() {
        String paragraph = "합성 연구소가 실험 결과와 향후 일정을 발표했다. ".repeat(5);
        String html = """
                <html><body><div>
                  <p>배너 안내</p><p>구독 안내</p>
                  <section><p>%s</p><p>%s</p></section>
                </div></body></html>
                """.formatted(paragraph, paragraph);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", paragraph.strip(), paragraph.strip()), body);
    }

    @Test
    void acceptsNestedArticleWhenDirectParagraphsAloneAreShort() {
        String firstParagraph = "A".repeat(80);
        String nestedParagraph = "B".repeat(80);
        String lastParagraph = "C".repeat(80);
        String html = """
                <html><body><div>
                  <p>%s</p><section><p>%s</p></section><p>%s</p>
                </div></body></html>
                """.formatted(firstParagraph, nestedParagraph, lastParagraph);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", firstParagraph, nestedParagraph, lastParagraph), body);
    }

    @Test
    void rejectsShortArticlePaddedByPublisherFooter() {
        String html = """
                <html><body><article><p>합성 연구소가 새 실험 일정을 발표했다.</p><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PUBLISHER_NOTICE, COPYRIGHT_NOTICE);

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/1"));
    }

    @Test
    void preservesOriginalArticleTextWithPublisherFooter() {
        String html = """
                <html><body><article><p>%s</p><p>%s</p><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH, PUBLISHER_NOTICE, COPYRIGHT_NOTICE);

        String body = ArticleContentExtractor.extract(html, "https://example.com/1");

        assertEquals(String.join("\n\n", PARAGRAPH.strip(), PARAGRAPH.strip(),
                PUBLISHER_NOTICE.strip(), COPYRIGHT_NOTICE.strip()), body);
    }

    /**
     * 페이월은 보통 로그인 안내 한 줄만 준다. 그걸 본문으로 저장하면 분석이 쓰레기를 읽는다.
     */
    @Test
    void returnsNullForPaywallStub() {
        String html = "<html><body><article><p>로그인 후 이용해 주세요.</p></article></body></html>";

        assertNull(ArticleContentExtractor.extract(html, "https://example.com/1"));
    }

    @Test
    void returnsNullForEmptyInput() {
        assertNull(ArticleContentExtractor.extract((String) null, "https://example.com/1"));
        assertNull(ArticleContentExtractor.extract((byte[]) null, "https://example.com/1"));
        assertNull(ArticleContentExtractor.extract("   ", "https://example.com/1"));
    }

    /**
     * 문단을 한 줄로 붙이면 M4의 문장 분할이 어려워진다.
     */
    @Test
    void keepsParagraphBoundaries() {
        String html = """
                <html><body><article><p>%s</p><p>%s</p></article></body></html>
                """.formatted(PARAGRAPH, PARAGRAPH);

        assertTrue(ArticleContentExtractor.extract(html, "https://example.com/1").contains("\n\n"));
    }
}
