package com.example.be.domain.sources.converter;

import com.example.be.domain.sources.dto.req.SourceReqDTO;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.domain.sources.entity.SearchProvider;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SourceConverter {

    private SourceConverter() {
    }

    /**
     * robotsStatus는 요청으로 받지 않는다. 등록 시점에는 robots.txt를 아직 확인하지 않았으므로 unknown으로 시작하고,
     * robots 재확인 API(M3)가 실제 확인 결과로 갱신한다.
     */
    public static Source toSource(SourceReqDTO.Create request) {
        return Source.builder()
                .sourceKind(normalizeKind(request.getSourceKind()))
                .name(trim(request.getName()))
                .urlTemplate(normalizeUrlTemplate(request.getUrlTemplate()))
                .country(upperTrim(request.getCountry()))
                .language(trim(request.getLanguage()))
                .crawlPolicy(request.getCrawlPolicy())
                .robotsStatus(Source.ROBOTS_STATUS_UNKNOWN)
                .reliabilityScore(request.getReliabilityScore())
                .active(request.getActive() == null || request.getActive())
                .build();
    }

    public static SourceResDTO.Created toCreated(Source source) {
        return SourceResDTO.Created.builder()
                .id(source.getId())
                .sourceKind(source.getSourceKind())
                .name(source.getName())
                .urlTemplate(source.getUrlTemplate())
                .country(source.getCountry())
                .language(source.getLanguage())
                .crawlPolicy(source.getCrawlPolicy())
                .robotsStatus(source.getRobotsStatus())
                .robotsCheckedAt(toOffsetDateTime(source.getRobotsCheckedAt()))
                .reliabilityScore(source.getReliabilityScore())
                .active(source.isActive())
                .build();
    }

    public static List<SourceResDTO.Summary> toSummaryList(List<Source> sources, Map<Long, Integer> linkedTopicCounts) {
        return sources.stream()
                .map(source -> toSummary(source, linkedTopicCounts.getOrDefault(source.getId(), 0)))
                .toList();
    }

    public static SourceResDTO.Summary toSummary(Source source, int linkedTopicCount) {
        return SourceResDTO.Summary.builder()
                .id(source.getId())
                .sourceKind(source.getSourceKind())
                .name(source.getName())
                .urlTemplate(source.getUrlTemplate())
                .country(source.getCountry())
                .language(source.getLanguage())
                .crawlPolicy(source.getCrawlPolicy())
                .robotsStatus(source.getRobotsStatus())
                .robotsCheckedAt(toOffsetDateTime(source.getRobotsCheckedAt()))
                .reliabilityScore(source.getReliabilityScore())
                .active(source.isActive())
                .linkedTopicCount(linkedTopicCount)
                .build();
    }

    /**
     * lastCollectedAt과 lastRunStatus는 news_collection_runs가 생기는 M3에서 채운다.
     */
    public static SourceResDTO.Detail toDetail(Source source) {
        return SourceResDTO.Detail.builder()
                .id(source.getId())
                .sourceKind(source.getSourceKind())
                .name(source.getName())
                .urlTemplate(source.getUrlTemplate())
                .country(source.getCountry())
                .language(source.getLanguage())
                .crawlPolicy(source.getCrawlPolicy())
                .robotsStatus(source.getRobotsStatus())
                .robotsCheckedAt(toOffsetDateTime(source.getRobotsCheckedAt()))
                .reliabilityScore(source.getReliabilityScore())
                .active(source.isActive())
                .linkedTopics(source.getTopics().stream().map(SourceConverter::toTopicBrief).toList())
                .lastCollectedAt(null)
                .lastRunStatus(null)
                .build();
    }

    public static SourceResDTO.Updated toUpdated(Source source) {
        return SourceResDTO.Updated.builder()
                .id(source.getId())
                .sourceKind(source.getSourceKind())
                .name(source.getName())
                .urlTemplate(source.getUrlTemplate())
                .country(source.getCountry())
                .language(source.getLanguage())
                .crawlPolicy(source.getCrawlPolicy())
                .robotsStatus(source.getRobotsStatus())
                .robotsCheckedAt(toOffsetDateTime(source.getRobotsCheckedAt()))
                .reliabilityScore(source.getReliabilityScore())
                .active(source.isActive())
                .build();
    }

    /**
     * deletedAt은 비활성 시각이 아니라 응답 시각이다. news_sources에 비활성 시각 컬럼이 없어서
     * 이미 비활성인 소스에 다시 삭제를 호출하면 매번 새 시각이 나간다.
     */
    public static SourceResDTO.Deleted toDeleted(Source source) {
        return SourceResDTO.Deleted.builder()
                .id(source.getId())
                .active(source.isActive())
                .deletedAt(OffsetDateTime.now(ApiTimeZone.ZONE))
                .build();
    }

    public static SourceResDTO.TopicBrief toTopicBrief(Topic topic) {
        return SourceResDTO.TopicBrief.builder()
                .id(topic.getId())
                .name(topic.getName())
                .build();
    }

    public static String normalizeKind(String sourceKind) {
        return upperTrim(sourceKind);
    }

    /**
     * provider 키는 대문자로 저장한다. url_template의 유니크 제약이 문자열 비교라
     * "naver"와 "NAVER"가 서로 다른 소스로 등록되면 같은 provider가 두 번 돌게 된다.
     */
    public static String normalizeUrlTemplate(String urlTemplate) {
        String trimmed = trim(urlTemplate);
        if (trimmed == null) {
            return null;
        }
        return SearchProvider.fromKey(trimmed) == null ? trimmed : trimmed.toUpperCase(Locale.ROOT);
    }

    private static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private static String upperTrim(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public static SourceResDTO.RobotsChecked toRobotsChecked(Long sourceId, RobotsDecision decision) {
        return SourceResDTO.RobotsChecked.builder()
                .sourceId(sourceId)
                .robotsStatus(decision.robotsStatus())
                .robotsCheckedAt(decision.checkedAt())
                .crawlDelaySeconds(decision.crawlDelaySeconds())
                .robotsTxtUrl(decision.robotsTxtUrl())
                .build();
    }
}
