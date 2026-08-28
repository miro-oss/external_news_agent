package com.example.be.domain.collection.converter;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.util.StringUtils;

/**
 * 중복 판정과 변경 판정에 쓰는 해시.
 *
 * <p>URL을 그대로 유니크 키로 걸 수 없어서 줄인다 — 검색 결과 URL은 수백 자가 되기도 하고,
 * Oracle 인덱스 키에는 길이 제한이 있다(§2-8).
 */
public final class ArticleHasher {

    private static final String ALGORITHM = "SHA-256";

    private ArticleHasher() {
    }

    public static String urlHash(String canonicalUrl) {
        return sha256(normalizeUrl(canonicalUrl));
    }

    /**
     * 추적용 정보만 제거해 같은 기사의 URL을 하나로 맞춘다.
     *
     * <p>경로와 일반 쿼리 파라미터는 기사 식별에 필요할 수 있으므로 순서와 인코딩을 그대로 둔다. 비어 있지
     * 않지만 URI로 파싱할 수 없는 값은 기존 수집 동작을 깨지 않도록 앞뒤 공백만 제거한다.
     */
    public static String normalizeUrl(String canonicalUrl) {
        if (!StringUtils.hasText(canonicalUrl)) {
            throw new IllegalArgumentException("canonicalUrl 없이 해시를 만들 수 없다.");
        }

        String trimmed = canonicalUrl.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.isOpaque()) {
                return trimmed;
            }
            StringBuilder normalized = new StringBuilder();
            if (uri.getScheme() != null) {
                normalized.append(uri.getScheme().toLowerCase(Locale.ROOT)).append(':');
            }
            if (uri.getRawAuthority() != null) {
                normalized.append("//").append(normalizeAuthority(uri));
            }
            if (uri.getRawPath() != null) {
                normalized.append(uri.getRawPath());
            }

            String query = removeTrackingParameters(uri.getRawQuery());
            if (query != null) {
                normalized.append('?').append(query);
            }
            return normalized.toString();
        } catch (URISyntaxException ignored) {
            return trimmed;
        }
    }

    /**
     * 내용이 바뀌었는지 판정할 지문.
     *
     * <p>본문이 있으면 본문으로 잡는다. 요약이나 발행일은 매체가 사소하게 손대는 일이 잦아
     * 그걸로 판정하면 바뀌지도 않은 기사가 매번 UPDATED가 된다.
     *
     * <p>본문이 없는 단계(METADATA_ONLY)에서는 <b>제목과 요약으로 지문을 만든다.</b> null을 두면
     * 비교할 게 없어져 같은 기사를 실행마다 새로 바뀐 것으로 보게 된다.
     */
    public static String contentHash(String title, String summary, String body) {
        if (StringUtils.hasText(body)) {
            return sha256(body.strip());
        }

        return sha256(nullToEmpty(title).strip() + "" + nullToEmpty(summary).strip());
    }

    /**
     * LLM 분석 입력의 버전을 식별하는 지문.
     *
     * <p>수집 변경 판정용 {@link #contentHash(String, String, String)}은 전문이 있으면 제목과 요약을
     * 의도적으로 제외한다. 분석 결과 재사용은 제목 변경도 놓치면 안 되므로 세 값을 모두 포함한다.
     */
    public static String analysisInputHash(String... fields) {
        StringBuilder encoded = new StringBuilder();
        for (String field : fields) {
            encoded.append(lengthPrefixed(nullToEmpty(field).strip()));
        }
        return sha256(encoded.toString());
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private static String normalizeAuthority(URI uri) {
        String authority = uri.getRawAuthority();
        String host = uri.getHost();
        if (host == null) {
            return authority;
        }

        int hostStart = authority.lastIndexOf(host);
        if (hostStart < 0) {
            return authority;
        }
        return authority.substring(0, hostStart)
                + host.toLowerCase(Locale.ROOT)
                + authority.substring(hostStart + host.length());
    }

    private static String removeTrackingParameters(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }

        List<String> retained = new ArrayList<>();
        for (String parameter : rawQuery.split("&", -1)) {
            if (!parameter.isEmpty() && !isTrackingParameter(parameter)) {
                retained.add(parameter);
            }
        }
        return retained.isEmpty() ? null : String.join("&", retained);
    }

    private static boolean isTrackingParameter(String parameter) {
        int equals = parameter.indexOf('=');
        String name = (equals < 0 ? parameter : parameter.substring(0, equals))
                .toLowerCase(Locale.ROOT);
        return name.startsWith("utm_") || name.equals("fbclid");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 반드시 제공한다. 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", e);
        }
    }
}
