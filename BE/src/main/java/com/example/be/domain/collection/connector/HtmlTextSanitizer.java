package com.example.be.domain.collection.connector;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;

/**
 * 검색 API가 돌려주는 제목·요약에서 마크업을 걷어낸다. 네이버는 검색어에 {@code <b>}를 씌워서 주고,
 * 본문에는 HTML 엔티티가 섞여 온다.
 */
public final class HtmlTextSanitizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private HtmlTextSanitizer() {
    }

    /**
     * <b>순서가 중요하다 — 태그 제거가 먼저, 엔티티 디코드가 나중이다.</b> 반대로 하면 {@code &lt;b&gt;}가
     * 진짜 태그로 변한 뒤 지워져서, 원문이 이스케이프해 둔 글자를 우리가 삼켜버린다.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }

        return HtmlUtils.htmlUnescape(HTML_TAG.matcher(text).replaceAll("")).strip();
    }
}
