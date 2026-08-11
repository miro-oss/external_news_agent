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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class TopicCommandServiceImpl implements TopicCommandService {

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

        return TopicConverter.toCreated(topicRepository.save(topic));
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
            throw new TopicException(TopicErrorCode.SOURCE_NOT_FOUND);
        }
        return sources;
    }

    private List<String> keywordsOrCurrent(List<String> requested, List<String> current) {
        return requested == null ? current : List.copyOf(requested);
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
