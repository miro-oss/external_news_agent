package com.example.be.domain.topics.service.query;

import com.example.be.domain.topics.converter.TopicConverter;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.domain.topics.repository.TopicSpecification;
import com.example.be.domain.topics.repository.TopicTrendJdbcRepository;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TopicQueryServiceImpl implements TopicQueryService {

    private final TopicRepository topicRepository;
    private final TopicTrendJdbcRepository topicTrendJdbcRepository;

    @Override
    public PageResponse<TopicResDTO.Summary> getTopics(Boolean active, String keyword, int page, int size) {
        validatePaging(page, size);

        Page<Topic> topics = topicRepository.findAll(
                TopicSpecification.filter(active, keyword),
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"))
        );
        List<Topic> content = topics.getContent();
        Map<Long, TopicTrendJdbcRepository.TopicTrendSnapshot> trendSnapshots = content.isEmpty()
                ? Map.of()
                : topicTrendJdbcRepository.findSnapshots(
                        content.stream().map(Topic::getId).toList(),
                        LocalDateTime.now(ApiTimeZone.ZONE));

        return PageResponse.of(
                TopicConverter.toSummaryList(
                        content,
                        countLinkedSources(content),
                        filterSeedKeywords(content, trendSnapshots)),
                page,
                size,
                topics.getTotalElements()
        );
    }

    @Override
    public TopicResDTO.Detail getTopic(Long topicId) {
        Topic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> new TopicException(TopicErrorCode.TOPIC_NOT_FOUND));

        return TopicConverter.toDetail(topic);
    }

    private Map<Long, Integer> countLinkedSources(List<Topic> topics) {
        if (topics.isEmpty()) {
            return Map.of();
        }

        List<Long> topicIds = topics.stream().map(Topic::getId).toList();
        return topicRepository.countLinkedSources(topicIds).stream()
                .collect(Collectors.toMap(
                        TopicRepository.LinkedSourceCount::getTopicId,
                        TopicRepository.LinkedSourceCount::getLinkedSourceCount
                ));
    }

    private Map<Long, TopicTrendJdbcRepository.TopicTrendSnapshot> filterSeedKeywords(
            List<Topic> topics,
            Map<Long, TopicTrendJdbcRepository.TopicTrendSnapshot> trendSnapshots) {
        if (topics.isEmpty() || trendSnapshots.isEmpty()) {
            return trendSnapshots;
        }

        Map<Long, TopicTrendJdbcRepository.TopicTrendSnapshot> result = new LinkedHashMap<>();
        for (Topic topic : topics) {
            TopicTrendJdbcRepository.TopicTrendSnapshot snapshot = trendSnapshots.get(topic.getId());
            if (snapshot == null) {
                continue;
            }

            Set<String> configuredKeywords = configuredKeywordSet(topic);
            List<TopicTrendJdbcRepository.TopicTrendKeyword> surgeKeywords = snapshot.surgeKeywords().stream()
                    .filter(item -> !configuredKeywords.contains(normalizeKeyword(item.keyword())))
                    .limit(5)
                    .toList();
            List<TopicTrendJdbcRepository.TopicRelatedKeyword> relatedKeywords = snapshot.relatedKeywords().stream()
                    .filter(item -> !configuredKeywords.contains(normalizeKeyword(item.keyword())))
                    .limit(5)
                    .toList();

            result.put(topic.getId(), new TopicTrendJdbcRepository.TopicTrendSnapshot(
                    surgeKeywords,
                    relatedKeywords
            ));
        }
        return result;
    }

    private Set<String> configuredKeywordSet(Topic topic) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addConfigured(result, topic.getName());
        addConfigured(result, topic.getRequiredKeywords());
        addConfigured(result, topic.getOptionalKeywords());
        addConfigured(result, topic.getExcludedKeywords());
        return result;
    }

    private void addConfigured(Set<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.add(normalizeKeyword(value));
    }

    private void addConfigured(Set<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        values.forEach(value -> addConfigured(target, value));
    }

    private String normalizeKeyword(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePaging(int page, int size) {
        if (page < PageResponse.DEFAULT_PAGE) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (size < PageResponse.MIN_SIZE || size > PageResponse.MAX_SIZE) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "size는 " + PageResponse.MIN_SIZE + " 이상 " + PageResponse.MAX_SIZE + " 이하여야 합니다.");
        }
    }
}
