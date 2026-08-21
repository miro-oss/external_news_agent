package com.example.be.domain.collection.controller;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.service.command.CollectionRunCommandService;
import com.example.be.domain.collection.service.command.CollectionRunStartResult;
import com.example.be.domain.collection.service.query.CollectionRunQueryService;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CollectionRunController.class)
class CollectionRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CollectionRunCommandService runCommandService;

    @MockitoBean
    private CollectionRunQueryService runQueryService;

    @Test
    void startRunRespondsWithCreatedEnvelope() throws Exception {
        when(runCommandService.startManualRun(any(CollectionRunReqDTO.Create.class)))
                .thenReturn(new CollectionRunStartResult(
                        GeneralSuccessCode.COLLECTION_STARTED,
                        createdRun()));

        mockMvc.perform(post("/api/news/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "topicIds": [1, 2],
                                  "idempotencyKey": "2026-08-10-manual-001",
                                  "forceRefresh": false,
                                  "plan": "PAID"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("COMMON201"))
                .andExpect(jsonPath("$.message").value("수집을 시작했습니다."))
                .andExpect(jsonPath("$.result.runId").value(42))
                .andExpect(jsonPath("$.result.status").value("RUNNING"))
                .andExpect(jsonPath("$.result.llmPlan").value("PAID"))
                .andExpect(jsonPath("$.result.targetTopicIds[0]").value(1))
                .andExpect(jsonPath("$.result.targetCombinationCount").value(6));
    }

    @Test
    void startRunRespondsWithOkWhenIdempotencyKeyIsAlreadyRunning() throws Exception {
        when(runCommandService.startManualRun(any(CollectionRunReqDTO.Create.class)))
                .thenReturn(new CollectionRunStartResult(
                        GeneralSuccessCode.COLLECTION_ALREADY_RUNNING,
                        alreadyRunning()));

        mockMvc.perform(post("/api/news/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idempotencyKey": "2026-08-10-manual-001" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("이미 진행 중인 수집입니다."))
                .andExpect(jsonPath("$.result.runId").value(42))
                .andExpect(jsonPath("$.result.targetTopicIds").doesNotExist())
                .andExpect(jsonPath("$.result.targetCombinationCount").doesNotExist());
    }

    @Test
    void startRunRespondsWithConflictRunCode() throws Exception {
        when(runCommandService.startManualRun(any(CollectionRunReqDTO.Create.class)))
                .thenThrow(new RunException(RunErrorCode.RUN_IN_PROGRESS, Map.of(
                        "conflictRunId", 41L,
                        "conflictTopicIds", List.of(1L))));

        mockMvc.perform(post("/api/news/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "topicIds": [1] }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("RUN409"))
                .andExpect(jsonPath("$.message").value("이미 실행 중인 수집이 있습니다."))
                .andExpect(jsonPath("$.result.conflictRunId").value(41))
                .andExpect(jsonPath("$.result.conflictTopicIds[0]").value(1));
    }

    @Test
    void getRunsRespondsWithPagedEnvelope() throws Exception {
        when(runQueryService.getRuns(eq("SUCCESS"), eq("MANUAL"), eq(null),
                any(OffsetDateTime.class), any(OffsetDateTime.class), eq(0), eq(20)))
                .thenReturn(PageResponse.of(List.of(summary()), 0, 20, 1L));

        mockMvc.perform(get("/api/news/runs")
                        .param("status", "SUCCESS")
                        .param("triggerType", "MANUAL")
                        .param("from", "2026-08-10T00:00:00+09:00")
                        .param("to", "2026-08-11T00:00:00+09:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].runId").value(42))
                .andExpect(jsonPath("$.result.content[0].warningCount").value(1))
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.hasNext").value(false));
    }

    @Test
    void getRunsRespondsWithBadRequestWhenPeriodIsInvalid() throws Exception {
        when(runQueryService.getRuns(eq(null), eq(null), eq(null),
                any(OffsetDateTime.class), any(OffsetDateTime.class), eq(0), eq(20)))
                .thenThrow(new GeneralException(GeneralErrorCode.BAD_REQUEST, "from은 to보다 이전이어야 합니다."));

        mockMvc.perform(get("/api/news/runs")
                        .param("from", "2026-08-11T00:00:00+09:00")
                        .param("to", "2026-08-10T00:00:00+09:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.message").value("from은 to보다 이전이어야 합니다."));
    }

    @Test
    void getRunRespondsWithDetailEnvelope() throws Exception {
        when(runQueryService.getRun(42L)).thenReturn(detail());

        mockMvc.perform(get("/api/news/runs/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.status").value("PARTIAL"))
                .andExpect(jsonPath("$.result.breakdown[0].topicName").value("HBM"))
                .andExpect(jsonPath("$.result.warnings[0].code").value("FULLTEXT_BLOCKED"));
    }

    @Test
    void getRunRespondsWithRunNotFoundCode() throws Exception {
        when(runQueryService.getRun(99L)).thenThrow(new RunException(RunErrorCode.RUN_NOT_FOUND));

        mockMvc.perform(get("/api/news/runs/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("RUN404"))
                .andExpect(jsonPath("$.message").value("수집 실행 이력을 찾을 수 없습니다."));
    }

    private CollectionRunResDTO.Created createdRun() {
        return CollectionRunResDTO.Created.builder()
                .runId(42L)
                .status("RUNNING")
                .triggerType("MANUAL")
                .idempotencyKey("2026-08-10-manual-001")
                .llmPlan("PAID")
                .targetTopicIds(List.of(1L, 2L))
                .targetCombinationCount(6)
                .startedAt(OffsetDateTime.of(2026, 8, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9)))
                .build();
    }

    private CollectionRunResDTO.Created alreadyRunning() {
        return CollectionRunResDTO.Created.builder()
                .runId(42L)
                .status("RUNNING")
                .triggerType("MANUAL")
                .idempotencyKey("2026-08-10-manual-001")
                .startedAt(OffsetDateTime.of(2026, 8, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9)))
                .build();
    }

    private CollectionRunResDTO.Summary summary() {
        return CollectionRunResDTO.Summary.builder()
                .runId(42L)
                .status("SUCCESS")
                .triggerType("MANUAL")
                .startedAt(OffsetDateTime.of(2026, 8, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9)))
                .finishedAt(OffsetDateTime.of(2026, 8, 10, 10, 3, 12, 0, ZoneOffset.ofHours(9)))
                .scannedCount(128)
                .newCount(14)
                .updatedCount(3)
                .skippedCount(111)
                .warningCount(1)
                .reportId(17L)
                .build();
    }

    private CollectionRunResDTO.Detail detail() {
        return CollectionRunResDTO.Detail.builder()
                .runId(42L)
                .status("PARTIAL")
                .triggerType("MANUAL")
                .idempotencyKey("2026-08-10-manual-001")
                .startedAt(OffsetDateTime.of(2026, 8, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9)))
                .finishedAt(OffsetDateTime.of(2026, 8, 10, 10, 3, 12, 0, ZoneOffset.ofHours(9)))
                .scannedCount(50)
                .newCount(9)
                .updatedCount(2)
                .skippedCount(39)
                .reportId(null)
                .breakdown(List.of(CollectionRunResDTO.Breakdown.builder()
                        .topicId(1L)
                        .topicName("HBM")
                        .sourceId(2L)
                        .sourceName("Google News RSS")
                        .scannedCount(50)
                        .newCount(9)
                        .updatedCount(2)
                        .status("SUCCESS")
                        .build()))
                .warnings(List.of(CollectionRunResDTO.Warning.builder()
                        .sourceId(2L)
                        .sourceName("Google News RSS")
                        .code("FULLTEXT_BLOCKED")
                        .message("페이월로 전문을 가져오지 못했습니다.")
                        .articleCount(5)
                        .occurredAt(OffsetDateTime.of(2026, 8, 10, 10, 2, 5, 0, ZoneOffset.ofHours(9)))
                        .build()))
                .build();
    }
}
