package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.converter.CollectedArticleConverter;
import com.example.be.domain.collection.connector.converter.HtmlTextSanitizer;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * RSS 2.0과 Atom 1.0을 같은 {@link CollectedArticle} 목록으로 바꾼다.
 *
 * <p>둘 다 받아야 한다. "RSS 주소"라고 알려진 URL이 실제로는 Atom인 경우가 흔하다.
 *
 * <p><b>남이 주는 XML이다.</b> 피드는 우리가 통제하지 않는 서버가 만든다. JDK 기본 설정은 DTD와 외부 엔티티를
 * 처리하므로 XXE(로컬 파일 유출)와 billion laughs(메모리 폭발)에 그대로 노출된다. 파서를 잠그고 쓴다.
 */
@Slf4j
public final class FeedParser {

    private FeedParser() {
    }

    /**
     * @throws FeedParseException XML로 읽지 못했을 때. "항목이 0건"과 구분하려고 예외로 알린다.
     */
    public static List<CollectedArticle> parse(String xml, String fallbackLanguage) {
        if (!StringUtils.hasText(xml)) {
            throw new FeedParseException("피드 본문이 비어 있다.");
        }

        Document document;
        try {
            document = secureDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            // HTML 페이지를 FEED로 등록해 둔 경우가 여기로 온다.
            throw new FeedParseException("피드를 XML로 읽지 못했다: " + e.getMessage(), e);
        }

        List<CollectedArticle> articles = new ArrayList<>();
        articles.addAll(parseItems(document, "item", fallbackLanguage));
        articles.addAll(parseItems(document, "entry", fallbackLanguage));
        return articles;
    }

    /**
     * DTD 자체를 거부한다. 외부 엔티티 차단만으로는 billion laughs(내부 엔티티 중첩)를 막지 못한다.
     */
    private static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // atom: 같은 접두사가 붙은 피드를 지역명으로 찾으려면 켜야 한다.
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static List<CollectedArticle> parseItems(Document document, String tagName, String fallbackLanguage) {
        NodeList nodes = document.getElementsByTagNameNS("*", tagName);
        List<CollectedArticle> articles = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                toArticle(element, fallbackLanguage).ifPresent(articles::add);
            }
        }

        return articles;
    }

    private static java.util.Optional<CollectedArticle> toArticle(Element element, String fallbackLanguage) {
        String link = linkOf(element);
        if (!StringUtils.hasText(link)) {
            log.debug("링크가 없는 항목을 건너뛴다. title={}", textOf(element, "title"));
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new CollectedArticle(
                HtmlTextSanitizer.sanitize(textOf(element, "title")),
                link.trim(),
                HtmlTextSanitizer.sanitize(summaryOf(element)),
                CollectedArticleConverter.toPublishedAt(publishedAtOf(element)),
                CollectedArticleConverter.toSourceName(link.trim()),
                fallbackLanguage
        ));
    }

    /**
     * RSS는 {@code <link>}에 URL이 텍스트로 들어 있고, Atom은 {@code <link href="...">} 속성이다.
     * Atom은 링크가 여러 개일 수 있어 {@code rel="alternate"}(또는 rel 없음)인 것을 원문으로 본다.
     */
    private static String linkOf(Element element) {
        NodeList links = element.getElementsByTagNameNS("*", "link");

        for (int i = 0; i < links.getLength(); i++) {
            if (!(links.item(i) instanceof Element link)) {
                continue;
            }

            String href = link.getAttribute("href");
            if (StringUtils.hasText(href)) {
                String rel = link.getAttribute("rel");
                if (!StringUtils.hasText(rel) || "alternate".equals(rel)) {
                    return href;
                }
                continue;
            }

            if (StringUtils.hasText(link.getTextContent())) {
                return link.getTextContent();
            }
        }

        // 링크 요소가 쓸모없으면 guid가 URL인 피드가 많다.
        String guid = textOf(element, "guid");
        return guid != null && guid.startsWith("http") ? guid : null;
    }

    private static String summaryOf(Element element) {
        String description = textOf(element, "description");
        if (StringUtils.hasText(description)) {
            return description;
        }

        String summary = textOf(element, "summary");
        return StringUtils.hasText(summary) ? summary : textOf(element, "content");
    }

    /**
     * RSS는 {@code pubDate}(RFC 2822), Atom은 {@code published} 또는 {@code updated}(ISO-8601)다.
     */
    private static String publishedAtOf(Element element) {
        String pubDate = textOf(element, "pubDate");
        if (StringUtils.hasText(pubDate)) {
            return pubDate;
        }

        String published = textOf(element, "published");
        return StringUtils.hasText(published) ? published : textOf(element, "updated");
    }

    /**
     * 자손 중 첫 번째 요소만 본다. {@code getElementsByTagName}은 깊이를 가리지 않아, 중첩된 항목이 있는
     * 피드에서 남의 값을 집을 수 있으므로 직계 자식을 우선한다.
     */
    private static String textOf(Element element, String tagName) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element candidate && tagName.equals(candidate.getLocalName() != null
                    ? candidate.getLocalName() : candidate.getTagName())) {
                return candidate.getTextContent();
            }
        }

        return null;
    }
}
