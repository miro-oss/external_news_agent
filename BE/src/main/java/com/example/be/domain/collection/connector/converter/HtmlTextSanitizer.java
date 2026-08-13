package com.example.be.domain.collection.connector.converter;

import org.jsoup.Jsoup;

/**
 * 검색 API가 돌려주는 제목·요약에서 마크업을 걷어낸다. 네이버는 검색어에 {@code <b>}를 씌워서 주고,
 * 본문에는 HTML 엔티티가 섞여 온다.
 */
public final class HtmlTextSanitizer {

    private HtmlTextSanitizer() {
    }

    /**
     * 파서로 태그를 걷고 텍스트만 남긴다.
     *
     * <p>예전에는 정규식({@code </?[a-zA-Z][^>]*>})이었다. <b>속성값 안의 {@code >}를 끊지 못해</b>
     * {@code <a title="x > y">링크</a>}가 {@code y">링크}로 남았다(PR #24 리뷰 항목). 여는 꺾쇠부터
     * 다음 꺾쇠까지를 태그로 보는 규칙으로는 속성 안의 꺾쇠를 구분할 수 없다 — 파서가 있어야 한다.
     * #29에서 Jsoup이 들어왔으므로 그걸 쓴다.
     *
     * <p>정규식이 지키던 계약은 그대로다. 파서도 여는 꺾쇠 뒤에 영문자가 와야 태그로 보기 때문에
     * {@code "A < B > C"} 같은 비교식이나 {@code "<7nm 공정에서 5nm로>"} 같은 표기는 살아남고,
     * <b>원문이 이스케이프해 둔 {@code &lt;b&gt;}는 태그로 승격되지 않고 글자로 남는다</b> —
     * 디코드된 결과를 다시 파싱하지 않기 때문이다.
     *
     * <p>달라진 점 하나: 연속 공백과 줄바꿈이 공백 하나로 접힌다. 제목·요약은 한 줄짜리라 영향이 없고,
     * 블록 태그로 붙어 있던 낱말이 {@code "HBM4양산"}처럼 눌어붙지 않는 쪽이 낫다.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }

        return Jsoup.parseBodyFragment(text).body().text();
    }
}
