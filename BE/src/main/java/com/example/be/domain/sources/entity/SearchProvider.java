package com.example.be.domain.sources.entity;

import java.util.Arrays;
import java.util.Locale;

/**
 * SEARCH 소스가 실제 HTTP 호출을 어느 어댑터에 맡기는지 나타내는 논리 provider 키다.
 *
 * <p>Naver는 인증 헤더가 필요하고 Tavily는 POST + JSON 바디를 쓰며 SerpAPI는 쿼리스트링에 API 키가 들어간다.
 * 셋 다 URL 템플릿 하나로 표현되지 않고, 키를 url_template에 적으면 시크릿이 DB로 들어간다.
 * 그래서 url_template에는 이 키만 두고 실제 요청은 어댑터가 소유한다.
 */
public enum SearchProvider {

    NAVER,
    TAVILY,
    SERPAPI;

    /**
     * url_template에 적힌 값이 provider 키인지 판단한다. 아니면 null을 돌려주고, 호출부가 URL 템플릿으로 취급한다.
     */
    public static SearchProvider fromKey(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(provider -> provider.name().equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
