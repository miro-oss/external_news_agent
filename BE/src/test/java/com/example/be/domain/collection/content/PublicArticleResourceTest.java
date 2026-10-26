package com.example.be.domain.collection.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublicArticleResourceTest {
    static final String ARTICLE = "https://publisher.example/news/view.php?idx=123456";
    static final String RESOURCE = "https://publisher.example/data/newsText/news/123/123456.json";
    static final String TITLE = "지역 제조기업 공동 연구 협약 체결";
    static final String BODY = "지역 제조기업들이 공동 연구를 위한 협약을 체결했다고 밝혔다. "
            + "이번 협약에 따라 기업과 연구기관은 새로운 장비의 성능을 검증하고 생산 현장의 활용 가능성을 확인한다. "
            + "참여 기관들은 기술 검증 결과를 바탕으로 다음 단계 연구 과제를 구체화할 계획이다. "
            + "연구를 주관하는 기관은 참여 업체가 공동으로 실험 결과를 확인할 수 있도록 자료를 제공한다고 설명했다. "
            + "업체들은 필요한 장비와 인력을 각각 지원하기로 했으며 후속 회의에서 구체적인 일정을 정할 예정이다.";

    static String page() {
        return """
                <html><head><link rel="canonical" href="%s"><meta property="og:title" content="%s">
                <script type="application/ld+json">{"@type":"NewsArticle","url":"%s",
                "mainEntityOfPage":{"@id":"%s"},"headline":"%s","isAccessibleForFree":true}</script>
                </head><body><div itemprop="articleBody"><div id="view_content_body"></div></div>
                <script>
                function showContent(data) { $("#view_content_body").html(data.view_content_body); }
                function viewContent() { $.ajax({ url: "%s", type: 'get', dataType: 'json',
                success: function(data) { if (!data) { alert('empty'); } else { showContent(data); } }
                }); }
                </script></body></html>
                """.formatted(ARTICLE, TITLE, ARTICLE, ARTICLE, TITLE, RESOURCE);
    }

    static String json() {
        return json(Map.of());
    }

    static String json(Map<String, Object> changes) {
        Map<String, Object> object = new LinkedHashMap<>(Map.of("idx", "123456", "main_subject", TITLE,
                "read_level", "0", "view_content_body", "<p>" + BODY + "</p>"));
        object.putAll(changes);
        return new ObjectMapper().writeValueAsString(object);
    }

    private PublicArticleResource.Resource find(String html) {
        return PublicArticleResource.find(html.getBytes(StandardCharsets.UTF_8), "UTF-8", ARTICLE);
    }

    @Test
    void findsOnlyTheCurrentExplicitlyPublicArticleResourceAndExtractsItsBody() {
        var resource = find(page());
        assertNotNull(resource);
        assertEquals(RESOURCE, resource.uri().toString());
        assertEquals("123456", resource.articleId());
        String body = PublicArticleResource.extract(json().getBytes(StandardCharsets.UTF_8), resource);
        assertNotNull(body);
        assertTrue(body.contains("후속 회의"));
        assertFalse(body.contains(TITLE));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://publisher.example/data/newsText/news/123/123456.json|https://other.example/data/newsText/news/123/123456.json",
            "https://publisher.example/data/newsText/news/123/123456.json|http://publisher.example/data/newsText/news/123/123456.json",
            "https://publisher.example/data/newsText/news/123/123456.json|https://publisher.example:8443/data/newsText/news/123/123456.json",
            "https://publisher.example/data/newsText/news/123/123456.json|https://publisher.example/data/newsText/news/123/123457.json",
            "https://publisher.example/data/newsText/news/123/123456.json|https://publisher.example/data/newsText/news/124/123456.json",
            "https://publisher.example/data/newsText/news/123/123456.json|https://publisher.example/data/newsText/news/123/123456.json?another=1",
            "\"isAccessibleForFree\":true|\"isAccessibleForFree\":false",
            "\"isAccessibleForFree\":true|\"isAccessibleForFree\":\"true\"",
            "type: 'get'|type: 'post'",
            "dataType: 'json'|dataType: 'html'",
            "data.view_content_body|data.recommendations"
    })
    void refusesUnprovenOrDifferentResources(String before, String after) {
        assertNull(find(page().replace(before, after)));
    }

    @Test
    void refusesMissingFreeDeclarationAndPaidSubparts() {
        assertNull(find(page().replace(",\"isAccessibleForFree\":true", "")));
        assertNull(find(page().replace("\"isAccessibleForFree\":true", "\"isAccessibleForFree\":true,\"hasPart\":{\"isAccessibleForFree\":false}")));
        assertNull(find(page().replace("<body>", "<body><div class='paywall'></div>")));
    }

    @Test
    void requiresCanonicalIdentityAndVisibleMatchingTitle() {
        assertNull(find(page().replace("href=\"" + ARTICLE + "\"", "href=\"" + ARTICLE.replace("123456", "123457") + "\"")));
        assertNull(find(page().replace("content=\"" + TITLE + "\"", "content=\"다른 기사\"")));
        assertNull(find(page().replace("\"mainEntityOfPage\":{\"@id\":\"" + ARTICLE, "\"mainEntityOfPage\":{\"@id\":\"https://other.example/wrong")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"idx", "main_subject", "read_level", "view_content_body"})
    void requiresTheResourceIdentityAccessAndBodySchema(String key) {
        var resource = find(page());
        assertNull(PublicArticleResource.extract(json(Map.of(key, "wrong")).getBytes(StandardCharsets.UTF_8), resource));
    }

    @Test
    void doesNotUseDescriptionsRelatedContentOrAccessNoticesAsABody() {
        var resource = find(page());
        assertNull(PublicArticleResource.extract(json(Map.of("view_content_body", "", "news_content", BODY,
                "lead_content", BODY)).getBytes(StandardCharsets.UTF_8), resource));
        assertNull(PublicArticleResource.extract(json(Map.of("view_content_body", "<p>구독 후 기사를 읽으실 수 있습니다.</p>"))
                .getBytes(StandardCharsets.UTF_8), resource));
        assertNull(PublicArticleResource.extract("[]".getBytes(StandardCharsets.UTF_8), resource));
        assertNull(PublicArticleResource.extract("malformed".getBytes(StandardCharsets.UTF_8), resource));
    }
}
