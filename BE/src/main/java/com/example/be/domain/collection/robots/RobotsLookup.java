package com.example.be.domain.collection.robots;

/**
 * robots.txt 조회 결과.
 *
 * <p>{@code reason}이 있으면 판단하지 못한 것이다. 이때 상태는 {@code unknown}이고,
 * <b>unknown은 수집을 막지 않는다</b> — robots.txt가 없거나 잠깐 안 열리는 사이트를 통째로 잃을 수는 없다.
 */
public record RobotsLookup(boolean resolved, String robotsTxtUrl, RobotsRules rules, String reason) {

    public static RobotsLookup fetched(String robotsTxtUrl, RobotsRules rules) {
        return new RobotsLookup(true, robotsTxtUrl, rules, null);
    }

    public static RobotsLookup unknown(String robotsTxtUrl, String reason) {
        return new RobotsLookup(false, robotsTxtUrl, RobotsRules.permitAll(), reason);
    }

    public boolean allows(String url) {
        return rules.allows(url);
    }
}
