package com.example.be.domain.collection.connector.converter;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;

/**
 * 검색 API가 돌려주는 제목·요약에서 마크업을 걷어낸다. 네이버는 검색어에 {@code <b>}를 씌워서 주고,
 * 본문에는 HTML 엔티티가 섞여 온다.
 */
public final class HtmlTextSanitizer {

    /**
     * 여는 꺾쇠 바로 뒤에 영문자가 와야 태그로 본다. HTML5도 같은 규칙이고, 이게 없으면
     * {@code "<7nm 공정에서 5nm로>"} 같은 본문이나 {@code "A < B > C"} 같은 비교식이 통째로 지워진다.
     */
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    private HtmlTextSanitizer() {
    }

    /**
     * <b>순서가 중요하다 — 태그 제거가 먼저, 엔티티 디코드가 나중이다.</b> 반대로 하면 {@code &lt;b&gt;}가
     * 진짜 태그로 변한 뒤 지워져서, 원문이 이스케이프해 둔 글자를 우리가 삼켜버린다.
     *
     * <p>속성값 안에 {@code >}가 든 태그({@code <a title="x > y">})는 정규식으로 끊을 수 없다. 세 provider가
     * 제목·요약에 보내는 건 {@code <b>} 같은 강조 태그뿐이라 여기서는 다루지 않는다. 임의의 HTML을 상대하는
     * 본문 추출(F6, M3)은 파서를 쓴다.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }

        return HtmlUtils.htmlUnescape(HTML_TAG.matcher(text).replaceAll("")).strip();
    }
}
