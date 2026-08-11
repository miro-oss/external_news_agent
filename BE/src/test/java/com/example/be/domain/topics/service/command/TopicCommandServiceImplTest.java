package com.example.be.domain.topics.service.command;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.dto.req.TopicReqDTO;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicCommandServiceImplTest {

    @Mock
    private TopicRepository topicRepository;

    @Mock
    private SourceRepository sourceRepository;

    @InjectMocks
    private TopicCommandServiceImpl topicCommandService;

    @Test
    void createTopicSavesTopicWithLinkedSources() {
        TopicReqDTO.Create request = createRequest();
        when(topicRepository.existsByName("HBM")).thenReturn(false);
        when(sourceRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(feedSource(), searchSource()));
        when(topicRepository.save(any(Topic.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TopicResDTO.Created result = topicCommandService.createTopic(request);

        assertEquals("HBM", result.getName());
        assertEquals(10, result.getBatchSize());
        assertEquals(60, result.getIntervalMinutes());
        assertTrue(result.isActive());
        assertEquals(2, result.getSources().size());
        assertEquals("SEARCH", result.getSources().get(1).getSourceKind());
    }

    @Test
    void createTopicAppliesDefaultBatchSizeAndInterval() {
        TopicReqDTO.Create request = new TopicReqDTO.Create();
        request.setName("DRAM");
        when(topicRepository.existsByName("DRAM")).thenReturn(false);
        when(topicRepository.save(any(Topic.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TopicResDTO.Created result = topicCommandService.createTopic(request);

        assertEquals(Topic.DEFAULT_BATCH_SIZE, result.getBatchSize());
        assertEquals(Topic.DEFAULT_INTERVAL_MINUTES, result.getIntervalMinutes());
        assertTrue(result.isActive());
        assertEquals(List.of(), result.getRequiredKeywords());
        assertEquals(List.of(), result.getSources());
        verify(sourceRepository, never()).findAllById(any());
    }

    @Test
    void createTopicRejectsDuplicatedName() {
        TopicReqDTO.Create request = createRequest();
        when(topicRepository.existsByName("HBM")).thenReturn(true);

        TopicException exception = assertThrows(TopicException.class,
                () -> topicCommandService.createTopic(request));

        assertEquals(TopicErrorCode.DUPLICATED_TOPIC_NAME, exception.getCode());
        verify(topicRepository, never()).save(any(Topic.class));
    }

    @Test
    void createTopicRejectsSearchSourceWithoutQueryText() {
        TopicReqDTO.Create request = createRequest();
        request.setQueryText(null);
        when(topicRepository.existsByName("HBM")).thenReturn(false);
        when(sourceRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(feedSource(), searchSource()));

        TopicException exception = assertThrows(TopicException.class,
                () -> topicCommandService.createTopic(request));

        assertEquals(TopicErrorCode.QUERY_TEXT_REQUIRED, exception.getCode());
    }

    @Test
    void createTopicRejectsOutOfRangeSchedule() {
        TopicReqDTO.Create request = createRequest();
        request.setBatchSize(0);

        TopicException exception = assertThrows(TopicException.class,
                () -> topicCommandService.createTopic(request));

        assertEquals(TopicErrorCode.INVALID_SCHEDULE, exception.getCode());
    }

    @Test
    void createTopicRejectsUnknownSourceId() {
        TopicReqDTO.Create request = createRequest();
        request.setSourceIds(List.of(1L, 99L));
        when(topicRepository.existsByName("HBM")).thenReturn(false);
        when(sourceRepository.findAllById(List.of(1L, 99L))).thenReturn(List.of(feedSource()));

        TopicException exception = assertThrows(TopicException.class,
                () -> topicCommandService.createTopic(request));

        assertEquals(TopicErrorCode.SOURCE_NOT_FOUND, exception.getCode());
    }

    @Test
    void updateTopicKeepsUntouchedFields() {
        TopicReqDTO.Update request = new TopicReqDTO.Update();
        request.setQueryText("HBM4 반도체");
        request.setExcludedKeywords(List.of("광고", "채용", "주가"));
        request.setBatchSize(20);
        request.setIntervalMinutes(30);

        Topic topic = existingTopic();
        when(topicRepository.findById(1L)).thenReturn(Optional.of(topic));

        TopicResDTO.Updated result = topicCommandService.updateTopic(1L, request);

        assertEquals("HBM", topic.getName());
        assertEquals("HBM4 반도체", topic.getQueryText());
        assertEquals(List.of("HBM"), topic.getRequiredKeywords());
        assertEquals(List.of("광고", "채용", "주가"), topic.getExcludedKeywords());
        assertEquals(20, topic.getBatchSize());
        assertEquals(30, topic.getIntervalMinutes());
        assertEquals("HBM4 반도체", result.getQueryText());
        assertEquals(20, result.getBatchSize());
    }

    @Test
    void updateTopicClearsKeywordsOnEmptyArray() {
        TopicReqDTO.Update request = new TopicReqDTO.Update();
        request.setRequiredKeywords(List.of());
        when(topicRepository.findById(1L)).thenReturn(Optional.of(existingTopic()));

        TopicResDTO.Updated result = topicCommandService.updateTopic(1L, request);

        assertEquals(List.of(), result.getRequiredKeywords());
    }

    @Test
    void updateTopicRejectsDuplicatedName() {
        TopicReqDTO.Update request = new TopicReqDTO.Update();
        request.setName("DRAM");
        when(topicRepository.findById(1L)).thenReturn(Optional.of(existingTopic()));
        when(topicRepository.existsByNameAndIdNot("DRAM", 1L)).thenReturn(true);

        TopicException exception = assertThrows(TopicException.class,
                () -> topicCommandService.updateTopic(1L, request));

        assertEquals(TopicErrorCode.DUPLICATED_TOPIC_NAME, exception.getCode());
    }

    @Test
    void updateTopicRejectsMissingTopic() {
        when(topicRepository.findById(99L)).thenReturn(Optional.empty());

        TopicException exception = assertThrows(TopicException.class,
                () -> topicCommandService.updateTopic(99L, new TopicReqDTO.Update()));

        assertEquals(TopicErrorCode.TOPIC_NOT_FOUND, exception.getCode());
    }

    @Test
    void updateActivationRejectsMissingActiveValue() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> topicCommandService.updateActivation(1L, new TopicReqDTO.Activation()));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("active 값은 필수입니다.", exception.getMessage());
        verify(topicRepository, never()).findById(any());
    }

    @Test
    void updateActivationTurnsTopicOff() {
        TopicReqDTO.Activation request = new TopicReqDTO.Activation();
        request.setActive(false);
        Topic topic = existingTopic();
        when(topicRepository.findById(1L)).thenReturn(Optional.of(topic));

        TopicResDTO.Activated result = topicCommandService.updateActivation(1L, request);

        assertEquals("HBM", result.getName());
        assertEquals(false, result.isActive());
        assertNull(result.getNextScheduledAt());
        assertEquals(false, topic.isActive());
    }

    @Test
    void deleteTopicReportsUnlinkedSourceCount() {
        Topic topic = existingTopic();
        topic.replaceSources(List.of(feedSource(), searchSource()));
        when(topicRepository.findById(1L)).thenReturn(Optional.of(topic));

        TopicResDTO.Deleted result = topicCommandService.deleteTopic(1L);

        assertEquals(1L, result.getId());
        assertEquals(2, result.getUnlinkedSourceCount());
        verify(topicRepository).delete(topic);
    }

    @Test
    void deleteTopicRejectsMissingTopic() {
        when(topicRepository.findById(99L)).thenReturn(Optional.empty());

        TopicException exception = assertThrows(TopicException.class,
                () -> topicCommandService.deleteTopic(99L));

        assertEquals(TopicErrorCode.TOPIC_NOT_FOUND, exception.getCode());
        verify(topicRepository, never()).delete(any(Topic.class));
    }

    private TopicReqDTO.Create createRequest() {
        TopicReqDTO.Create request = new TopicReqDTO.Create();
        request.setName("HBM");
        request.setQueryText("HBM 반도체");
        request.setRequiredKeywords(List.of("HBM"));
        request.setOptionalKeywords(List.of("SK하이닉스", "삼성전자", "마이크론"));
        request.setExcludedKeywords(List.of("광고", "채용"));
        request.setBatchSize(10);
        request.setIntervalMinutes(60);
        request.setActive(true);
        request.setSourceIds(List.of(1L, 2L));
        return request;
    }

    private Topic existingTopic() {
        return Topic.builder()
                .id(1L)
                .name("HBM")
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스"))
                .excludedKeywords(List.of("광고", "채용"))
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build();
    }

    private Source feedSource() {
        return Source.builder()
                .id(1L)
                .name("ETNews 반도체")
                .sourceKind(Source.KIND_FEED)
                .urlTemplate("https://example.com/rss")
                .language("ko")
                .robotsStatus("allowed")
                .active(true)
                .build();
    }

    private Source searchSource() {
        return Source.builder()
                .id(2L)
                .name("Google News RSS")
                .sourceKind(Source.KIND_SEARCH)
                .urlTemplate("https://example.com?q={query}")
                .language("ko")
                .robotsStatus("allowed")
                .active(true)
                .build();
    }
}
