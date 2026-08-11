package com.example.be.domain.topics.service.query;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicQueryServiceImplTest {

    @Mock
    private TopicRepository topicRepository;

    @InjectMocks
    private TopicQueryServiceImpl topicQueryService;

    @Test
    void getTopicsReturnsPagedSummariesWithLinkedSourceCount() {
        when(topicRepository.findAll(ArgumentMatchers.<Specification<Topic>>any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(topic()), PageRequest.of(0, 20), 1L));
        when(topicRepository.countLinkedSources(List.of(1L))).thenReturn(List.of(linkedSourceCount(1L, 4)));

        PageResponse<TopicResDTO.Summary> result = topicQueryService.getTopics(true, null, 0, 20);

        assertEquals(1, result.getContent().size());
        assertEquals(4, result.getContent().get(0).getLinkedSourceCount());
        assertEquals(0, result.getPage());
        assertEquals(20, result.getSize());
        assertEquals(1L, result.getTotalElements());
        assertEquals(1, result.getTotalPages());
        assertFalse(result.isHasNext());
    }

    @Test
    void getTopicsFallsBackToZeroWhenNoSourceLinked() {
        when(topicRepository.findAll(ArgumentMatchers.<Specification<Topic>>any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(topic()), PageRequest.of(0, 20), 1L));
        when(topicRepository.countLinkedSources(List.of(1L))).thenReturn(List.of());

        PageResponse<TopicResDTO.Summary> result = topicQueryService.getTopics(null, "HBM", 0, 20);

        assertEquals(0, result.getContent().get(0).getLinkedSourceCount());
    }

    @Test
    void getTopicsSkipsCountQueryWhenPageIsEmpty() {
        when(topicRepository.findAll(ArgumentMatchers.<Specification<Topic>>any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

        PageResponse<TopicResDTO.Summary> result = topicQueryService.getTopics(null, null, 0, 20);

        assertEquals(List.of(), result.getContent());
        assertEquals(0, result.getTotalPages());
        verify(topicRepository, never()).countLinkedSources(any());
    }

    @Test
    void getTopicsRejectsOutOfRangeSize() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> topicQueryService.getTopics(null, null, 0, 101));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("size는 1 이상 100 이하여야 합니다.", exception.getMessage());
    }

    @Test
    void getTopicsRejectsNegativePage() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> topicQueryService.getTopics(null, null, -1, 20));

        assertEquals("page는 0 이상이어야 합니다.", exception.getMessage());
    }

    @Test
    void getTopicReturnsDetailWithLinkedSources() {
        Topic topic = topic();
        topic.replaceSources(List.of(source()));
        when(topicRepository.findById(1L)).thenReturn(Optional.of(topic));

        TopicResDTO.Detail result = topicQueryService.getTopic(1L);

        assertEquals(1L, result.getId());
        assertEquals("HBM", result.getName());
        assertEquals(1, result.getSources().size());
        assertEquals("ETNews 반도체", result.getSources().get(0).getName());
        assertEquals("allowed", result.getSources().get(0).getRobotsStatus());
    }

    @Test
    void getTopicRejectsMissingTopic() {
        when(topicRepository.findById(99L)).thenReturn(Optional.empty());

        TopicException exception = assertThrows(TopicException.class,
                () -> topicQueryService.getTopic(99L));

        assertEquals(TopicErrorCode.TOPIC_NOT_FOUND, exception.getCode());
    }

    private TopicRepository.LinkedSourceCount linkedSourceCount(Long topicId, int linkedSourceCount) {
        return new TopicRepository.LinkedSourceCount() {

            @Override
            public Long getTopicId() {
                return topicId;
            }

            @Override
            public int getLinkedSourceCount() {
                return linkedSourceCount;
            }
        };
    }

    private Topic topic() {
        return Topic.builder()
                .id(1L)
                .name("HBM")
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스"))
                .excludedKeywords(List.of("광고"))
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .lastCollectedAt(LocalDateTime.of(2026, 8, 10, 8, 0))
                .build();
    }

    private Source source() {
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
}
