package com.example.be.domain.topics.converter;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.dto.req.TopicReqDTO;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.dto.res.TopicSourceResDTO;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class TopicConverter {

    private TopicConverter() {
    }

    public static Topic toTopic(TopicReqDTO.Create request) {
        return Topic.builder()
                .name(trim(request.getName()))
                .queryText(trim(request.getQueryText()))
                .requiredKeywords(normalizeKeywords(request.getRequiredKeywords()))
                .optionalKeywords(normalizeKeywords(request.getOptionalKeywords()))
                .excludedKeywords(normalizeKeywords(request.getExcludedKeywords()))
                .batchSize(request.getBatchSize() == null ? Topic.DEFAULT_BATCH_SIZE : request.getBatchSize())
                .intervalMinutes(request.getIntervalMinutes() == null
                        ? Topic.DEFAULT_INTERVAL_MINUTES
                        : request.getIntervalMinutes())
                .active(request.getActive() == null || request.getActive())
                .build();
    }

    public static TopicResDTO.Created toCreated(Topic topic) {
        return TopicResDTO.Created.builder()
                .id(topic.getId())
                .name(topic.getName())
                .queryText(topic.getQueryText())
                .requiredKeywords(topic.getRequiredKeywords())
                .optionalKeywords(topic.getOptionalKeywords())
                .excludedKeywords(topic.getExcludedKeywords())
                .batchSize(topic.getBatchSize())
                .intervalMinutes(topic.getIntervalMinutes())
                .active(topic.isActive())
                .sources(topic.getSources().stream().map(TopicConverter::toSourceBrief).toList())
                .build();
    }

    public static List<TopicResDTO.Summary> toSummaryList(List<Topic> topics, Map<Long, Integer> linkedSourceCounts) {
        return topics.stream()
                .map(topic -> toSummary(topic, linkedSourceCounts.getOrDefault(topic.getId(), 0)))
                .toList();
    }

    public static TopicResDTO.Summary toSummary(Topic topic, int linkedSourceCount) {
        return TopicResDTO.Summary.builder()
                .id(topic.getId())
                .name(topic.getName())
                .queryText(topic.getQueryText())
                .requiredKeywords(topic.getRequiredKeywords())
                .optionalKeywords(topic.getOptionalKeywords())
                .excludedKeywords(topic.getExcludedKeywords())
                .batchSize(topic.getBatchSize())
                .intervalMinutes(topic.getIntervalMinutes())
                .active(topic.isActive())
                .linkedSourceCount(linkedSourceCount)
                .lastCollectedAt(toOffsetDateTime(topic.getLastCollectedAt()))
                .build();
    }

    public static TopicResDTO.Detail toDetail(Topic topic) {
        return TopicResDTO.Detail.builder()
                .id(topic.getId())
                .name(topic.getName())
                .queryText(topic.getQueryText())
                .requiredKeywords(topic.getRequiredKeywords())
                .optionalKeywords(topic.getOptionalKeywords())
                .excludedKeywords(topic.getExcludedKeywords())
                .batchSize(topic.getBatchSize())
                .intervalMinutes(topic.getIntervalMinutes())
                .active(topic.isActive())
                .lastCollectedAt(toOffsetDateTime(topic.getLastCollectedAt()))
                .sources(topic.getSources().stream().map(TopicConverter::toSourceDetail).toList())
                .build();
    }

    public static TopicResDTO.Updated toUpdated(Topic topic) {
        return TopicResDTO.Updated.builder()
                .id(topic.getId())
                .name(topic.getName())
                .queryText(topic.getQueryText())
                .requiredKeywords(topic.getRequiredKeywords())
                .optionalKeywords(topic.getOptionalKeywords())
                .excludedKeywords(topic.getExcludedKeywords())
                .batchSize(topic.getBatchSize())
                .intervalMinutes(topic.getIntervalMinutes())
                .active(topic.isActive())
                .build();
    }

    public static TopicResDTO.Activated toActivated(Topic topic) {
        return TopicResDTO.Activated.builder()
                .id(topic.getId())
                .name(topic.getName())
                .active(topic.isActive())
                .nextScheduledAt(toNextScheduledAt(topic))
                .build();
    }

    public static TopicResDTO.Deleted toDeleted(Long topicId, int unlinkedSourceCount) {
        return TopicResDTO.Deleted.builder()
                .id(topicId)
                .deletedAt(OffsetDateTime.now(ApiTimeZone.ZONE))
                .unlinkedSourceCount(unlinkedSourceCount)
                .build();
    }

    public static TopicResDTO.SourcesLinked toSourcesLinked(Topic topic, int addedCount, int removedCount) {
        List<TopicResDTO.SourceBrief> sources = topic.getSources().stream()
                .map(TopicConverter::toSourceBrief)
                .toList();

        return TopicResDTO.SourcesLinked.builder()
                .topicId(topic.getId())
                .sources(sources)
                .addedCount(addedCount)
                .removedCount(removedCount)
                .combinationCount(sources.size())
                .build();
    }

    /**
     * queryText는 SEARCH 소스에 넘길 검색어라 FEED 조합에서는 내려보내지 않는다.
     * lastCollectedCount는 조합별 수집 건수라 news_collection_runs가 생기는 M3에서 채운다.
     */
    public static TopicSourceResDTO.Combination toCombination(TopicRepository.CombinationRow row) {
        boolean searchKind = Source.KIND_SEARCH.equals(row.getSourceKind());

        return TopicSourceResDTO.Combination.builder()
                .topicId(row.getTopicId())
                .topicName(row.getTopicName())
                .sourceId(row.getSourceId())
                .sourceName(row.getSourceName())
                .sourceKind(row.getSourceKind())
                .queryText(searchKind ? row.getQueryText() : null)
                .batchSize(row.getBatchSize())
                .intervalMinutes(row.getIntervalMinutes())
                .active(row.getTopicActive() && row.getSourceActive())
                .lastCollectedAt(toOffsetDateTime(row.getLastCollectedAt()))
                .lastCollectedCount(null)
                .build();
    }

    public static TopicResDTO.SourceBrief toSourceBrief(Source source) {
        return TopicResDTO.SourceBrief.builder()
                .id(source.getId())
                .name(source.getName())
                .sourceKind(source.getSourceKind())
                .build();
    }

    public static TopicResDTO.SourceDetail toSourceDetail(Source source) {
        return TopicResDTO.SourceDetail.builder()
                .id(source.getId())
                .name(source.getName())
                .sourceKind(source.getSourceKind())
                .language(source.getLanguage())
                .robotsStatus(source.getRobotsStatus())
                .active(source.isActive())
                .build();
    }

    /**
     * 스케줄러(M7) 도입 전까지는 마지막 수집 시각과 주기로 다음 실행 예정 시각을 계산한다.
     * 비활성 주제는 스케줄러가 건너뛰므로 null이다.
     */
    private static OffsetDateTime toNextScheduledAt(Topic topic) {
        if (!topic.isActive()) {
            return null;
        }
        LocalDateTime lastCollectedAt = topic.getLastCollectedAt();
        return lastCollectedAt == null
                ? OffsetDateTime.now(ApiTimeZone.ZONE)
                : toOffsetDateTime(lastCollectedAt.plusMinutes(topic.getIntervalMinutes()));
    }

    private static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    /**
     * 요청 배열에 null이나 공백 항목이 섞여 와도 그대로 저장하지 않는다.
     */
    public static List<String> normalizeKeywords(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
