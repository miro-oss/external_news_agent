package com.example.be.domain.collection.connector.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * provider마다 다른 건 응답 필드 이름뿐이고, 발행일과 매체명을 우리 값으로 바꾸는 방식은 같다.
 */
@Slf4j
public final class CollectedArticleConverter {

    private static final DateTimeFormatter[] PUBLISHED_AT_FORMATS = {
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME
    };

    private CollectedArticleConverter() {
    }

    /**
     * 네이버 {@code pubDate}는 RFC 2822고 나머지는 ISO-8601로도 온다. 어느 쪽으로도 읽히지 않으면 null이다 —
     * 현재 시각으로 채우면 발행일이 틀렸다는 사실이 사라지고 "최근 기사" 필터가 조용히 망가진다.
     */
    public static OffsetDateTime toPublishedAt(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        for (DateTimeFormatter format : PUBLISHED_AT_FORMATS) {
            try {
                return OffsetDateTime.parse(text.trim(), format);
            } catch (DateTimeParseException ignored) {
                // 다음 형식으로 넘어간다.
            }
        }

        log.debug("발행일을 읽지 못해 비워 둔다. publishedAt={}", text);
        return null;
    }

    /**
     * 매체명을 따로 주지 않는 provider가 있어 원문 URL의 호스트로 대신한다.
     */
    public static String toSourceName(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }

        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
