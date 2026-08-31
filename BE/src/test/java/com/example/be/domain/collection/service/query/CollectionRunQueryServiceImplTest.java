package com.example.be.domain.collection.service.query;

import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionRunQueryServiceImplTest {

    @Mock
    private CollectionRunRepository runRepository;

    @Mock
    private CollectionRunItemRepository runItemRepository;

    @Mock
    private CollectionRunCoverageService coverageService;

    @InjectMocks
    private CollectionRunQueryServiceImpl runQueryService;

    @Test
    void getRunsMapsRunsWithWarningCounts() {
        CollectionRun run = run(RunStatus.SUCCESS);
        when(runRepository.findAll(anyRunSpecification(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 20), 1L));
        when(runRepository.countWarnings(List.of(42L))).thenReturn(List.of(warningCount(42L, 3)));

        PageResponse<CollectionRunResDTO.Summary> result = runQueryService.getRuns(
                "SUCCESS", "MANUAL", null,
                OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 8, 11, 0, 0, 0, 0, ZoneOffset.ofHours(9)),
                0,
                20);

        assertEquals(1, result.getContent().size());
        assertEquals(42L, result.getContent().get(0).getRunId());
        assertEquals("SUCCESS", result.getContent().get(0).getStatus());
        assertEquals(3, result.getContent().get(0).getWarningCount());
        assertEquals(1L, result.getTotalElements());
        assertFalse(result.isHasNext());
    }

    @Test
    void getRunsRejectsInvalidPeriod() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> runQueryService.getRuns(null, null, null,
                        OffsetDateTime.parse("2026-08-11T00:00:00+09:00"),
                        OffsetDateTime.parse("2026-08-10T00:00:00+09:00"),
                        0,
                        20));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("from은 to보다 이전이어야 합니다.", exception.getMessage());
        verify(runRepository, never()).findAll(anyRunSpecification(), any(PageRequest.class));
    }

    @Test
    void getRunsRejectsInvalidStatus() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> runQueryService.getRuns("DONE", null, null, null, null, 0, 20));

        assertEquals("status는 PENDING / RUNNING / SUCCESS / PARTIAL / FAILED 중 하나여야 합니다.",
                exception.getMessage());
    }

    @Test
    void getRunMapsBreakdownAndWarnings() {
        CollectionRun run = run(RunStatus.PARTIAL);
        Topic topic = topic(1L, "HBM");
        Source source = source(2L, "Google News RSS");
        CollectionRunItem item = CollectionRunItem.builder()
                .id(100L)
                .run(run)
                .topic(topic)
                .source(source)
                .status(RunItemStatus.SUCCESS)
                .scannedCount(50)
                .newCount(9)
                .updatedCount(2)
                .build();
        run.addWarning(CollectionRunWarning.builder()
                .source(source)
                .code(CollectionRunWarning.CODE_FULLTEXT_BLOCKED)
                .message("페이월로 전문을 가져오지 못했습니다.")
                .articleCount(5)
                .occurredAt(LocalDateTime.of(2026, 8, 10, 10, 2, 5))
                .build());
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(runItemRepository.findByRunIdOrderByIdAsc(42L)).thenReturn(List.of(item));
        when(coverageService.calculate(42L)).thenReturn(new CollectionRunCoverage(
                1, 1, java.math.BigDecimal.ONE,
                1, 1, 1, java.math.BigDecimal.ONE,
                1, 0, java.math.BigDecimal.ONE, 30));

        CollectionRunResDTO.Detail result = runQueryService.getRun(42L);

        assertEquals(42L, result.getRunId());
        assertEquals("PARTIAL", result.getStatus());
        assertEquals("HBM", result.getBreakdown().get(0).getTopicName());
        assertEquals(50, result.getBreakdown().get(0).getScannedCount());
        assertEquals("FULLTEXT_BLOCKED", result.getWarnings().get(0).getCode());
        assertEquals(5, result.getWarnings().get(0).getArticleCount());
        assertEquals(java.math.BigDecimal.ONE, result.getCoverage().getIssueAssignmentRate());
    }

    @Test
    void getRunRejectsMissingRun() {
        when(runRepository.findById(99L)).thenReturn(Optional.empty());

        RunException exception = assertThrows(RunException.class, () -> runQueryService.getRun(99L));

        assertEquals(RunErrorCode.RUN_NOT_FOUND, exception.getCode());
    }

    private CollectionRun run(RunStatus status) {
        return CollectionRun.builder()
                .id(42L)
                .status(status)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey("manual-key")
                .startedAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .finishedAt(LocalDateTime.of(2026, 8, 10, 10, 3, 12))
                .scannedCount(50)
                .newCount(9)
                .updatedCount(2)
                .skippedCount(39)
                .reportId(17L)
                .build();
    }

    private Topic topic(Long id, String name) {
        return Topic.builder()
                .id(id)
                .name(name)
                .queryText(name)
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build();
    }

    private Source source(Long id, String name) {
        return Source.builder()
                .id(id)
                .sourceKind(Source.KIND_FEED)
                .name(name)
                .urlTemplate("https://example.com/" + id)
                .active(true)
                .build();
    }

    private CollectionRunRepository.WarningCount warningCount(Long runId, int warningCount) {
        return new CollectionRunRepository.WarningCount() {
            @Override
            public Long getRunId() {
                return runId;
            }

            @Override
            public int getWarningCount() {
                return warningCount;
            }
        };
    }

    private Specification<CollectionRun> anyRunSpecification() {
        return any();
    }
}
