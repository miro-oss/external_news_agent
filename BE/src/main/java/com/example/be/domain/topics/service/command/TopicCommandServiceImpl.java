package com.example.be.domain.topics.service.command;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.converter.TopicConverter;
import com.example.be.domain.topics.dto.req.TopicReqDTO;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TopicCommandServiceImpl implements TopicCommandService {

    private static final String TOPIC_NAME_CONSTRAINT = "UQ_NEWS_TOPIC_NAME";

    private final TopicRepository topicRepository;
    private final SourceRepository sourceRepository;

    @Override
    public TopicResDTO.Created createTopic(TopicReqDTO.Create request) {
        Topic topic = TopicConverter.toTopic(request);
        validateName(topic.getName());
        validateQueryText(topic.getQueryText());
        validateSchedule(topic.getBatchSize(), topic.getIntervalMinutes());

        if (topicRepository.existsByName(topic.getName())) {
            throw new TopicException(TopicErrorCode.DUPLICATED_TOPIC_NAME);
        }

        List<Source> sources = findSources(request.getSourceIds());
        validateQueryTextForSearchSources(topic.getQueryText(), sources);
        topic.replaceSources(sources);

        return TopicConverter.toCreated(saveTopic(topic));
    }

    @Override
    public TopicResDTO.Updated updateTopic(Long topicId, TopicReqDTO.Update request) {
        Topic topic = getTopic(topicId);

        String name = request.getName() == null ? topic.getName() : request.getName().trim();
        String queryText = request.getQueryText() == null ? topic.getQueryText() : request.getQueryText().trim();
        List<String> requiredKeywords = keywordsOrCurrent(request.getRequiredKeywords(), topic.getRequiredKeywords());
        List<String> optionalKeywords = keywordsOrCurrent(request.getOptionalKeywords(), topic.getOptionalKeywords());
        List<String> excludedKeywords = keywordsOrCurrent(request.getExcludedKeywords(), topic.getExcludedKeywords());
        int batchSize = request.getBatchSize() == null ? topic.getBatchSize() : request.getBatchSize();
        int intervalMinutes = request.getIntervalMinutes() == null
                ? topic.getIntervalMinutes()
                : request.getIntervalMinutes();
        boolean active = request.getActive() == null ? topic.isActive() : request.getActive();

        validateName(name);
        validateQueryText(queryText);
        validateSchedule(batchSize, intervalMinutes);

        if (!topic.getName().equals(name) && topicRepository.existsByNameAndIdNot(name, topicId)) {
            throw new TopicException(TopicErrorCode.DUPLICATED_TOPIC_NAME);
        }
        validateQueryTextForSearchSources(queryText, topic.getSources());

        topic.update(name, queryText, requiredKeywords, optionalKeywords, excludedKeywords,
                batchSize, intervalMinutes, active);
        flushTopic();

        return TopicConverter.toUpdated(topic);
    }

    @Override
    public TopicResDTO.Activated updateActivation(Long topicId, TopicReqDTO.Activation request) {
        if (request.getActive() == null) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "active 값은 필수입니다.");
        }

        Topic topic = getTopic(topicId);
        topic.changeActive(request.getActive());

        return TopicConverter.toActivated(topic);
    }

    /**
     * 연결 목록을 통째로 교체한다. 전달한 목록에 없던 기존 연결은 제거되고, 빈 배열이면 전부 해제된다.
     */
    @Override
    public TopicResDTO.SourcesLinked replaceSources(Long topicId, TopicReqDTO.SourceLink request) {
        Topic topic = getTopic(topicId);

        Set<Long> beforeIds = topic.getSources().stream().map(Source::getId).collect(Collectors.toSet());
        List<Source> sources = findSources(request.getSourceIds());
        validateQueryTextForSearchSources(topic.getQueryText(), sources);

        Set<Long> afterIds = sources.stream().map(Source::getId).collect(Collectors.toSet());
        int addedCount = (int) afterIds.stream().filter(id -> !beforeIds.contains(id)).count();
        int removedCount = (int) beforeIds.stream().filter(id -> !afterIds.contains(id)).count();

        topic.replaceSources(sources);

        return TopicConverter.toSourcesLinked(topic, addedCount, removedCount);
    }

    /**
     * 수집이 진행 중인 주제를 막는 TOPIC409 검사는 news_collection_runs가 생기는 M3에서 추가한다.
     */
    @Override
    public TopicResDTO.Deleted deleteTopic(Long topicId) {
        Topic topic = getTopic(topicId);
        int unlinkedSourceCount = topic.getLinkedSourceCount();

        topicRepository.delete(topic);

        return TopicConverter.toDeleted(topicId, unlinkedSourceCount);
    }

    private Topic getTopic(Long topicId) {
        return topicRepository.findById(topicId)
                .orElseThrow(() -> new TopicException(TopicErrorCode.TOPIC_NOT_FOUND));
    }

    /**
     * 주제명 중복은 위에서 미리 막지만, 동시 요청은 유니크 제약에서만 걸린다.
     * 그 경우에도 500이 아니라 TOPIC409로 응답하도록 서비스 안에서 flush하고 변환한다.
     */
    private Topic saveTopic(Topic topic) {
        try {
            return topicRepository.saveAndFlush(topic);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicatedName(exception);
        }
    }

    private void flushTopic() {
        try {
            topicRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicatedName(exception);
        }
    }

    private RuntimeException translateDuplicatedName(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause.getMessage();

        if (message != null && message.toUpperCase(Locale.ROOT).contains(TOPIC_NAME_CONSTRAINT)) {
            return new TopicException(TopicErrorCode.DUPLICATED_TOPIC_NAME);
        }
        return exception;
    }

    private List<Source> findSources(List<Long> requestedSourceIds) {
        if (requestedSourceIds == null || requestedSourceIds.isEmpty()) {
            return List.of();
        }

        List<Long> sourceIds = requestedSourceIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<Source> sources = sourceRepository.findAllById(sourceIds);
        if (sources.size() != sourceIds.size()) {
            throw new TopicException(TopicErrorCode.SOURCE_NOT_FOUND,
                    Map.of("notFoundSourceIds", notFoundSourceIds(sourceIds, sources)));
        }
        return sources;
    }

    /**
     * 어떤 소스가 없는지 알려주지 않으면 호출자가 id를 하나씩 지워가며 찾아야 한다.
     */
    private List<Long> notFoundSourceIds(List<Long> requestedIds, List<Source> foundSources) {
        Set<Long> foundIds = foundSources.stream().map(Source::getId).collect(Collectors.toSet());
        return requestedIds.stream().filter(id -> !foundIds.contains(id)).toList();
    }

    private List<String> keywordsOrCurrent(List<String> requested, List<String> current) {
        return requested == null ? current : TopicConverter.normalizeKeywords(requested);
    }

    private void validateName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "name은 필수입니다.");
        }
        if (name.length() > Topic.MAX_NAME_LENGTH) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "name은 " + Topic.MAX_NAME_LENGTH + "자 이하여야 합니다.");
        }
    }

    private void validateQueryText(String queryText) {
        if (queryText != null && queryText.length() > Topic.MAX_QUERY_TEXT_LENGTH) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "queryText는 " + Topic.MAX_QUERY_TEXT_LENGTH + "자 이하여야 합니다.");
        }
    }

    private void validateSchedule(int batchSize, int intervalMinutes) {
        boolean invalidBatchSize = batchSize < Topic.MIN_BATCH_SIZE || batchSize > Topic.MAX_BATCH_SIZE;
        boolean invalidInterval = intervalMinutes < Topic.MIN_INTERVAL_MINUTES;

        if (invalidBatchSize || invalidInterval) {
            throw new TopicException(TopicErrorCode.INVALID_SCHEDULE);
        }
    }

    private void validateQueryTextForSearchSources(String queryText, List<Source> sources) {
        boolean hasSearchSource = sources.stream().anyMatch(Source::isSearchKind);
        if (hasSearchSource && !StringUtils.hasText(queryText)) {
            throw new TopicException(TopicErrorCode.QUERY_TEXT_REQUIRED);
        }
    }
}
