package com.example.be.domain.sources.service.query;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.exception.SourceException;
import com.example.be.domain.sources.exception.code.SourceErrorCode;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceQueryServiceImplTest {

    @Mock
    private SourceRepository sourceRepository;

    @Mock
    private CollectionRunItemRepository runItemRepository;

    @InjectMocks
    private SourceQueryServiceImpl sourceQueryService;

    @Test
    void getSourcesReturnsPagedSummariesWithLinkedTopicCount() {
        when(sourceRepository.findAll(ArgumentMatchers.<Specification<Source>>any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(source()), PageRequest.of(0, 20), 1L));
        when(sourceRepository.countLinkedTopics(List.of(1L))).thenReturn(List.of(linkedTopicCount(1L, 3)));

        PageResponse<SourceResDTO.Summary> result = sourceQueryService.getSources("FEED", true, null, 0, 20);

        assertEquals(1, result.getContent().size());
        assertEquals("ETNews 반도체", result.getContent().get(0).getName());
        assertEquals(3, result.getContent().get(0).getLinkedTopicCount());
        assertEquals(30, result.getContent().get(0).getCrawlPolicy().maxArticlesPerRun());
        assertEquals(1L, result.getTotalElements());
        assertFalse(result.isHasNext());
    }

    @Test
    void getSourcesFallsBackToZeroWhenNoTopicLinked() {
        when(sourceRepository.findAll(ArgumentMatchers.<Specification<Source>>any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(source()), PageRequest.of(0, 20), 1L));
        when(sourceRepository.countLinkedTopics(List.of(1L))).thenReturn(List.of());

        PageResponse<SourceResDTO.Summary> result = sourceQueryService.getSources(null, null, "ETNews", 0, 20);

        assertEquals(0, result.getContent().get(0).getLinkedTopicCount());
    }

    @Test
    void getSourcesSkipsCountQueryWhenPageIsEmpty() {
        when(sourceRepository.findAll(ArgumentMatchers.<Specification<Source>>any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

        PageResponse<SourceResDTO.Summary> result = sourceQueryService.getSources(null, null, null, 0, 20);

        assertEquals(List.of(), result.getContent());
        verify(sourceRepository, never()).countLinkedTopics(any());
    }

    @Test
    void getSourcesRejectsUnknownSourceKind() {
        SourceException exception = assertThrows(SourceException.class,
                () -> sourceQueryService.getSources("RSS", null, null, 0, 20));

        assertEquals(SourceErrorCode.INVALID_SOURCE_KIND, exception.getCode());
    }

    @Test
    void getSourcesRejectsOutOfRangeSize() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> sourceQueryService.getSources(null, null, null, 0, 101));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("size는 1 이상 100 이하여야 합니다.", exception.getMessage());
    }

    @Test
    void getSourcesRejectsNegativePage() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> sourceQueryService.getSources(null, null, null, -1, 20));

        assertEquals("page는 0 이상이어야 합니다.", exception.getMessage());
    }

    @Test
    void getSourceReturnsDetailWithLinkedTopics() {
        Source source = Source.builder()
                .id(1L)
                .sourceKind(Source.KIND_FEED)
                .name("ETNews 반도체")
                .urlTemplate("https://rss.etnews.com/Section902.xml")
                .country("KR")
                .language("ko")
                .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .active(true)
                .topics(List.of(topic(1L, "HBM"), topic(2L, "DRAM")))
                .build();
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));

        SourceResDTO.Detail result = sourceQueryService.getSource(1L);

        assertEquals(2, result.getLinkedTopics().size());
        assertEquals("HBM", result.getLinkedTopics().get(0).getName());
        assertNull(result.getLastCollectedAt());
        assertNull(result.getLastRunStatus());
    }

    @Test
    void getSourceReturnsLatestCollectionRunState() {
        Source source = source();
        CollectionRun latestRun = CollectionRun.builder()
                .id(42L)
                .status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL)
                .startedAt(LocalDateTime.of(2026, 8, 18, 14, 5, 7))
                .build();
        CollectionRunItem latestRunItem = CollectionRunItem.builder()
                .id(101L)
                .run(latestRun)
                .topic(topic(1L, "HBM"))
                .source(source)
                .status(RunItemStatus.SUCCESS)
                .build();
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));
        when(runItemRepository.findFirstBySourceIdOrderByRunStartedAtDescRunIdDescIdDesc(1L))
                .thenReturn(Optional.of(latestRunItem));

        SourceResDTO.Detail result = sourceQueryService.getSource(1L);

        assertEquals(OffsetDateTime.of(2026, 8, 18, 14, 5, 7, 0, ZoneOffset.ofHours(9)),
                result.getLastCollectedAt());
        assertEquals("SUCCESS", result.getLastRunStatus());
    }

    @Test
    void getSourceRejectsMissingSource() {
        when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

        SourceException exception = assertThrows(SourceException.class, () -> sourceQueryService.getSource(99L));

        assertEquals(SourceErrorCode.SOURCE_NOT_FOUND, exception.getCode());
    }

    private SourceRepository.LinkedTopicCount linkedTopicCount(Long sourceId, int linkedTopicCount) {
        return new SourceRepository.LinkedTopicCount() {

            @Override
            public Long getSourceId() {
                return sourceId;
            }

            @Override
            public int getLinkedTopicCount() {
                return linkedTopicCount;
            }
        };
    }

    private Source source() {
        return Source.builder()
                .id(1L)
                .sourceKind(Source.KIND_FEED)
                .name("ETNews 반도체")
                .urlTemplate("https://rss.etnews.com/Section902.xml")
                .country("KR")
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .reliabilityScore(new BigDecimal("0.85"))
                .active(true)
                .build();
    }

    private Topic topic(Long id, String name) {
        return Topic.builder()
                .id(id)
                .name(name)
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build();
    }
}
