package com.example.be.domain.topics.service.query;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.dto.res.TopicSourceResDTO;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicSourceQueryServiceImplTest {

    @Mock
    private TopicRepository topicRepository;

    @InjectMocks
    private TopicSourceQueryServiceImpl topicSourceQueryService;

    @Test
    void getCombinationsFlattensTopicAndSourcePairs() {
        when(topicRepository.findCombinations(eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(Source.KIND_SEARCH, true, true)), PageRequest.of(0, 20), 1L));

        TopicSourceResDTO.CombinationPage result =
                topicSourceQueryService.getCombinations(null, null, null, 0, 20);

        assertEquals(1, result.getContent().size());
        TopicSourceResDTO.Combination combination = result.getContent().get(0);
        assertEquals("HBM", combination.getTopicName());
        assertEquals("Google News RSS", combination.getSourceName());
        assertEquals(Source.KIND_SEARCH, combination.getSourceKind());
        assertEquals("HBM 반도체", combination.getQueryText());
        assertTrue(combination.isActive());
        assertEquals(1L, result.getTotalElements());
        assertEquals(1L, result.getCombinationCount());
        assertFalse(result.isHasNext());
    }

    @Test
    void getCombinationsOmitsQueryTextForFeedPairs() {
        when(topicRepository.findCombinations(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(row(Source.KIND_SEARCH, true, true), row(Source.KIND_FEED, true, true)),
                        PageRequest.of(0, 20), 2L));

        TopicSourceResDTO.CombinationPage result =
                topicSourceQueryService.getCombinations(null, null, null, 0, 20);

        assertEquals("HBM 반도체", result.getContent().get(0).getQueryText());
        assertNull(result.getContent().get(1).getQueryText());
    }

    @Test
    void getCombinationsMarksPairInactiveWhenSourceIsOff() {
        when(topicRepository.findCombinations(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(Source.KIND_SEARCH, true, false)), PageRequest.of(0, 20), 1L));

        TopicSourceResDTO.CombinationPage result =
                topicSourceQueryService.getCombinations(null, null, null, 0, 20);

        assertFalse(result.getContent().get(0).isActive());
    }

    @Test
    void getCombinationsLeavesLastCollectedCountEmptyUntilRunsExist() {
        when(topicRepository.findCombinations(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(Source.KIND_SEARCH, true, true)), PageRequest.of(0, 20), 1L));

        TopicSourceResDTO.CombinationPage result =
                topicSourceQueryService.getCombinations(null, null, null, 0, 20);

        assertNull(result.getContent().get(0).getLastCollectedCount());
    }

    @Test
    void getCombinationsPassesTopicFilterAfterCheckingItExists() {
        when(topicRepository.existsById(1L)).thenReturn(true);
        when(topicRepository.findCombinations(eq(1L), eq(2L), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(Source.KIND_SEARCH, true, true)), PageRequest.of(0, 20), 1L));

        TopicSourceResDTO.CombinationPage result =
                topicSourceQueryService.getCombinations(1L, 2L, true, 0, 20);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void getCombinationsRejectsUnknownTopicFilter() {
        when(topicRepository.existsById(99L)).thenReturn(false);

        TopicException exception = assertThrows(TopicException.class,
                () -> topicSourceQueryService.getCombinations(99L, null, null, 0, 20));

        assertEquals(TopicErrorCode.TOPIC_NOT_FOUND, exception.getCode());
        verify(topicRepository, never()).findCombinations(any(), any(), any(), any(Pageable.class));
    }

    @Test
    void getCombinationsRejectsOutOfRangeSize() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> topicSourceQueryService.getCombinations(null, null, null, 0, 101));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("size는 1 이상 100 이하여야 합니다.", exception.getMessage());
    }

    @Test
    void getCombinationsRejectsNegativePage() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> topicSourceQueryService.getCombinations(null, null, null, -1, 20));

        assertEquals("page는 0 이상이어야 합니다.", exception.getMessage());
    }

    private TopicRepository.CombinationRow row(String sourceKind, boolean topicActive, boolean sourceActive) {
        return new TopicRepository.CombinationRow() {

            @Override
            public Long getTopicId() {
                return 1L;
            }

            @Override
            public String getTopicName() {
                return "HBM";
            }

            @Override
            public Long getSourceId() {
                return 2L;
            }

            @Override
            public String getSourceName() {
                return "Google News RSS";
            }

            @Override
            public String getSourceKind() {
                return sourceKind;
            }

            @Override
            public String getQueryText() {
                return "HBM 반도체";
            }

            @Override
            public int getBatchSize() {
                return 10;
            }

            @Override
            public int getIntervalMinutes() {
                return 60;
            }

            @Override
            public boolean getTopicActive() {
                return topicActive;
            }

            @Override
            public boolean getSourceActive() {
                return sourceActive;
            }

            @Override
            public LocalDateTime getLastCollectedAt() {
                return LocalDateTime.of(2026, 8, 10, 8, 0);
            }
        };
    }
}
