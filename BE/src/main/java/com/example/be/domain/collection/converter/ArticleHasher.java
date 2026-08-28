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
import java.util.Set;

import org.springframework.util.StringUtils;

/**
 * 중복 판정과 변경 판정에 쓰는 해시.
 *
 * <p>URL을 그대로 유니크 키로 걸 수 없어서 줄인다 — 검색 결과 URL은 수백 자가 되기도 하고,
 * Oracle 인덱스 키에는 길이 제한이 있다(§2-8).
 */
public final class ArticleHasher {

    private static final String ALGORITHM = "SHA-256";
    private static final String TRACKING_PARAMETER_PREFIX = "utm_";
    private static final Set<String> TRACKING_PARAMETER_NAMES = Set.of(
            "_ga",
            "dclid",
            "fbclid",
            "gclid",
            "igshid",
            "mc_cid",
            "mc_eid",
            "msclkid",
            "spm",
            "yclid");

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
     * HTTP(S) URL이 아닌 opaque URI도 프래그먼트를 포함한 원문을 보존한다.
     *
     * <p><b>이 규칙을 바꾸면 이미 저장된 {@code urlHash}와 호환되지 않는다.</b> 로컬 개발 DB는 초기화 후
     * 재수집하고, 운영 데이터가 생긴 뒤에는 충돌 병합을 포함한 별도 백필이 선행돼야 한다.
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
            return normalizeRegistryAuthority(authority);
        }

        int hostStart = authority.lastIndexOf(host);
        if (hostStart < 0) {
            return authority;
        }
        return authority.substring(0, hostStart)
                + host.toLowerCase(Locale.ROOT)
                + authority.substring(hostStart + host.length());
    }

    /** URI가 유효한 DNS host로 해석하지 못한 authority에서도 userinfo와 port는 건드리지 않는다. */
    private static String normalizeRegistryAuthority(String authority) {
        int hostStart = authority.lastIndexOf('@') + 1;
        int hostEnd = authority.length();
        if (hostStart < hostEnd && authority.charAt(hostStart) == '[') {
            int bracketEnd = authority.indexOf(']', hostStart);
            if (bracketEnd >= 0) {
                hostEnd = bracketEnd + 1;
            }
        } else {
            int firstColon = authority.indexOf(':', hostStart);
            int lastColon = authority.lastIndexOf(':');
            if (firstColon >= hostStart && firstColon == lastColon) {
                hostEnd = firstColon;
            }
        }
        if (hostStart >= hostEnd) {
            return authority;
        }
        return authority.substring(0, hostStart)
                + authority.substring(hostStart, hostEnd).toLowerCase(Locale.ROOT)
                + authority.substring(hostEnd);
    }

    private static String removeTrackingParameters(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }

        List<String> retained = new ArrayList<>();
        for (String parameter : rawQuery.split("&", -1)) {
            if (!isTrackingParameter(parameter)) {
                retained.add(parameter);
            }
        }
        boolean hasParameter = retained.stream().anyMatch(parameter -> !parameter.isEmpty());
        return hasParameter ? String.join("&", retained) : null;
    }

    private static boolean isTrackingParameter(String parameter) {
        int equals = parameter.indexOf('=');
        String name = (equals < 0 ? parameter : parameter.substring(0, equals))
                .toLowerCase(Locale.ROOT);
        return name.startsWith(TRACKING_PARAMETER_PREFIX) || TRACKING_PARAMETER_NAMES.contains(name);
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
