package com.example.be.domain.feedback.service;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.feedback.dto.req.FeedbackReqDTO.EventSubmitRequest;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.util.FeedbackTokens;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportEventFeedbackServiceTest {
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final FeedbackStore store = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory snapshots = mock(ReportEventSnapshotFactory.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ReportEventFeedbackService service = new ReportEventFeedbackService(reports, findings, relevance,
            new ReportEventFeedbackProjection(store, snapshots), store, json);
    private final NewsReport report = NewsReport.builder().id(17L).title("보고서").reportScope(ReportScope.DAILY)
            .reportStatus(ReportStatus.GENERATED).build();
    private final Item rejected = item('a', 0);
    private final Item retained = item('b', 1);

    @BeforeEach void setup() {
        when(reports.findById(17L)).thenReturn(Optional.of(report));
        when(reports.findByIdForUpdate(17L)).thenReturn(Optional.of(report));
        when(snapshots.capture(eq(report), any())).thenReturn(List.of(rejected, retained));
        when(store.eventFeedback(17L)).thenReturn(List.of(feedback(rejected, "hash", Status.COMPLETED, "CONFIRMED_ERROR")));
    }

    @Test void contextReindexesDisplayedEventsButPreservesReviewHistoryAndOriginalEventKey() {
        var context = service.context(17L);
        assertEquals(1, context.events().size());
        var event = context.events().getFirst();
        assertEquals(0, event.eventIndex());
        assertEquals(retained.event().key(), event.eventKey());
        assertEquals(retained.event().sourceFindingIds(), event.sourceFindingIds());
        assertEquals(1, retained.event().index());
        assertEquals(rejected.event().key(), context.feedback().getFirst().eventKey());
        assertEquals("CONFIRMED_ERROR", context.feedback().getFirst().verdict());
        verify(store, never()).submitEvent(anyLong(), any(), any(), anyString(), anyString(), anyString(), any());
        verify(store, never()).enqueue(anyString(), anyString(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test void submittingAfterExclusionQueuesTheOriginalUncompactedSnapshot() {
        when(store.submitEvent(eq(17L), any(), eq(Category.SUMMARY_ERROR), eq("의견"), eq("new-key"), anyString(), any()))
                .thenReturn(9L);
        when(store.byId(9L)).thenReturn(Optional.of(feedback(retained, "hash", Status.PENDING, null)));
        var response = service.submit(17L, new EventSubmitRequest(retained.event().key(), "SUMMARY_ERROR", " 의견 ", "new-key"));
        assertEquals(retained.event().key(), response.eventKey());
        var captured = ArgumentCaptor.forClass(Item.class);
        verify(store).submitEvent(eq(17L), captured.capture(), eq(Category.SUMMARY_ERROR), eq("의견"), eq("new-key"), anyString(), any());
        assertSame(retained, captured.getValue());
        assertEquals(1, captured.getValue().event().index());
        assertEquals(retained.event().key(), captured.getValue().event().key());
        verify(store).enqueue(eq("review:9"), eq("REVIEW"), eq(9L), isNull(), eq(17L), isNull(), same(retained), any());
    }

    @Test void retryOfAnExcludedEventReturnsItsImmutableReviewWithoutRecapturing() {
        String hash = FeedbackTokens.hash(json.writeValueAsString(List.of(rejected.event().key(), "SUMMARY_ERROR", "의견")));
        var saved = feedback(rejected, hash, Status.COMPLETED, "CONFIRMED_ERROR");
        when(store.eventByRequest(17L, "original-key")).thenReturn(Optional.of(saved));
        var response = service.submit(17L, new EventSubmitRequest(rejected.event().key(), "SUMMARY_ERROR", "의견", "original-key"));
        assertEquals(saved.id(), response.id());
        assertEquals("CONFIRMED_ERROR", response.verdict());
        verifyNoInteractions(snapshots);
        verify(store, never()).submitEvent(anyLong(), any(), any(), anyString(), anyString(), anyString(), any());
    }

    private static Item item(char key, int index) {
        return new Item(null, null, null, List.of(), null, null, null, null, null, null, null,
                new EventContext(String.valueOf(key).repeat(64), index, "사건 " + key, "요약 " + key, "이유 " + key,
                        List.of(100L + index), List.of(), List.of(), null));
    }
    private static Feedback feedback(Item item, String hash, Status status, String verdict) {
        return new Feedback(9L, null, null, 17L, null, Category.SUMMARY_ERROR, "의견", false, hash, status, verdict,
                "설명", LocalDateTime.of(2026, 10, 1, 12, 0), item, item.event().key());
    }
}
