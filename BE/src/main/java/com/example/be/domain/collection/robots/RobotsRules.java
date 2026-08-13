package com.example.be.domain.collection.robots;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * robots.txt 한 장을 우리 user-agent 기준으로 해석한 결과.
 *
 * <p>표준이 아니라 관례에 가까운 형식이라 파서가 관대해야 한다. 모르는 지시문은 무시하고,
 * 읽다가 이상한 줄을 만나도 나머지를 계속 읽는다. 파싱에 실패했다고 수집을 막으면
 * robots.txt를 엉성하게 써 둔 매체를 통째로 잃는다.
 */
public record RobotsRules(List<String> disallowedPaths, List<String> allowedPaths, Duration crawlDelay) {

    private static final String USER_AGENT_DIRECTIVE = "user-agent:";
    private static final String DISALLOW_DIRECTIVE = "disallow:";
    private static final String ALLOW_DIRECTIVE = "allow:";
    private static final String CRAWL_DELAY_DIRECTIVE = "crawl-delay:";
    private static final String WILDCARD_AGENT = "*";

    /** robots.txt가 없거나 읽지 못했을 때. 아무것도 막지 않는다. */
    public static RobotsRules permitAll() {
        return new RobotsRules(List.of(), List.of(), null);
    }

    /**
     * 우리 user-agent에 해당하는 블록과 {@code *} 블록만 모은다.
     *
     * <p>이름이 정확히 맞는 블록이 있으면 그것만 쓰는 게 규약이지만, 여기서는 둘 다 모아 더 엄격하게 본다.
     * 남의 서버를 상대로는 덜 긁는 쪽으로 틀리는 게 낫다.
     */
    public static RobotsRules parse(String robotsTxt, String userAgent) {
        if (!StringUtils.hasText(robotsTxt)) {
            return permitAll();
        }

        List<String> disallowed = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        Duration crawlDelay = null;
        boolean applies = false;

        for (String rawLine : robotsTxt.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }

            String lowered = line.toLowerCase(Locale.ROOT);
            if (lowered.startsWith(USER_AGENT_DIRECTIVE)) {
                String agent = valueOf(line, USER_AGENT_DIRECTIVE);
                applies = WILDCARD_AGENT.equals(agent) || agent.equalsIgnoreCase(userAgent);
                continue;
            }

            if (!applies) {
                continue;
            }

            if (lowered.startsWith(DISALLOW_DIRECTIVE)) {
                String path = valueOf(line, DISALLOW_DIRECTIVE);
                // "Disallow:" 뒤가 비어 있으면 "아무것도 막지 않는다"는 뜻이다. 전체 차단이 아니다.
                if (!path.isEmpty()) {
                    disallowed.add(path);
                }
            } else if (lowered.startsWith(ALLOW_DIRECTIVE)) {
                String path = valueOf(line, ALLOW_DIRECTIVE);
                if (!path.isEmpty()) {
                    allowed.add(path);
                }
            } else if (lowered.startsWith(CRAWL_DELAY_DIRECTIVE)) {
                crawlDelay = parseCrawlDelay(valueOf(line, CRAWL_DELAY_DIRECTIVE), crawlDelay);
            }
        }

        return new RobotsRules(List.copyOf(disallowed), List.copyOf(allowed), crawlDelay);
    }

    /**
     * 더 긴 규칙이 이긴다. {@code Disallow: /} + {@code Allow: /rss/}면 /rss/는 허용이다.
     */
    public boolean allows(String url) {
        String path = pathOf(url);

        int longestDisallow = longestMatch(disallowedPaths, path);
        if (longestDisallow < 0) {
            return true;
        }

        return longestMatch(allowedPaths, path) >= longestDisallow;
    }

    public boolean hasCrawlDelay() {
        return crawlDelay != null;
    }

    private static int longestMatch(List<String> patterns, String path) {
        int longest = -1;
        for (String pattern : patterns) {
            if (matches(pattern, path)) {
                longest = Math.max(longest, pattern.length());
            }
        }

        return longest;
    }

    /**
     * robots.txt의 {@code *}와 {@code $}만 다룬다. 정규식으로 바꾸지 않는 이유는 남이 쓴 문자열을
     * 정규식으로 컴파일하면 특수문자 하나에 파서가 죽기 때문이다.
     */
    private static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String cleaned = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        String[] segments = cleaned.split("\\*", -1);

        int cursor = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }

            int found = i == 0 ? (path.startsWith(segment) ? 0 : -1) : path.indexOf(segment, cursor);
            if (found < 0) {
                return false;
            }
            cursor = found + segment.length();
        }

        return !anchored || cursor == path.length();
    }

    private static Duration parseCrawlDelay(String value, Duration current) {
        try {
            double seconds = Double.parseDouble(value);
            if (seconds <= 0) {
                return current;
            }

            Duration parsed = Duration.ofMillis((long) (seconds * 1000));
            // 블록이 여러 개면 더 긴 쪽을 지킨다.
            return current == null || parsed.compareTo(current) > 0 ? parsed : current;
        } catch (NumberFormatException e) {
            return current;
        }
    }

    private static String pathOf(String url) {
        try {
            String path = new URI(url).getRawPath();
            return StringUtils.hasText(path) ? path : "/";
        } catch (URISyntaxException e) {
            return "/";
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static String valueOf(String line, String directive) {
        return line.substring(directive.length()).trim();
    }
}
