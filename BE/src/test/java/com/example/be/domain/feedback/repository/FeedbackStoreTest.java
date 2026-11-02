package com.example.be.domain.feedback.repository;

import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
import com.example.be.domain.feedback.model.FeedbackModels.Item;
import com.example.be.global.database.OracleInClause;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper json = mock(ObjectMapper.class);
    private final FeedbackStore store = new FeedbackStore(jdbc, json);

    @Test
    void emptyReportIdsDoNotQueryTheDatabase() {
        assertTrue(store.eventReviewStates(List.of()).isEmpty());
        verifyNoInteractions(jdbc, json);
    }

    @Test
    void reviewStatesKeepPendingFrozenInputForRevisedReportsWithoutLoadingDiagnosis() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(8L);
        when(row.getLong("report_id")).thenReturn(17L);
        when(row.getString("category")).thenReturn("SUMMARY_ERROR");
        when(row.getString("status")).thenReturn("PENDING");
        when(row.getString("input_json")).thenReturn("saved-input");
        var input = mock(Item.class);
        when(json.readValue("saved-input", Item.class)).thenReturn(input);
        when(row.getString("event_key")).thenReturn("a".repeat(64));
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 12, 0)));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Feedback>>any(), any(Object[].class)))
                .thenAnswer(call -> List.of(call.<RowMapper<Feedback>>getArgument(1).mapRow(row, 0)));

        var feedback = store.eventReviewStates(List.of(17L)).getFirst();

        assertEquals(17L, feedback.reportId());
        assertEquals(com.example.be.domain.feedback.model.FeedbackModels.Status.PENDING, feedback.status());
        assertNull(feedback.verdict());
        assertEquals("a".repeat(64), feedback.eventKey());
        assertSame(input, feedback.input());
        assertNull(feedback.diagnosis());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<Feedback>>any(), any(Object[].class));
        assertFalse(sql.getValue().contains("SELECT *"));
        assertTrue(sql.getValue().contains("input_json"));
        assertFalse(sql.getValue().contains("review_json"));
        assertFalse(sql.getValue().contains("diagnosis"));
        verify(row).getString("input_json");
        verify(row, never()).getString("review_json");
        verify(row, never()).getString("diagnosis");
        verify(json).readValue("saved-input", Item.class);
    }

    @Test
    void malformedHistoricalInputDoesNotBreakReportListsOrInventFallbackEvidence() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(8L);
        when(row.getLong("report_id")).thenReturn(17L);
        when(row.getString("category")).thenReturn("PREFERENCE");
        when(row.getString("status")).thenReturn("FAILED");
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 12, 0)));
        when(row.getString("input_json")).thenReturn("invalid-input");
        when(row.getString("event_key")).thenReturn("a".repeat(64));
        when(json.readValue("invalid-input", Item.class)).thenThrow(new IllegalArgumentException("invalid input"));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Feedback>>any(), any(Object[].class)))
                .thenAnswer(call -> List.of(call.<RowMapper<Feedback>>getArgument(1).mapRow(row, 0)));

        var review = store.eventReviewStates(List.of(17L)).getFirst();

        assertNull(review.input());
        assertEquals("a".repeat(64), review.eventKey());
    }

    @Test
    void reviewStateQueriesDeduplicateAndStayBelowTheOracleInLimit() {
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 1001).boxed().toList());
        ids.add(1L);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Feedback>>any(), any(Object[].class)))
                .thenReturn(List.of());

        assertTrue(store.eventReviewStates(ids).isEmpty());

        var sql = ArgumentCaptor.forClass(String.class);
        var arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<Feedback>>any(), arguments.capture());
        assertEquals(List.of(OracleInClause.BATCH_SIZE, 1001 - OracleInClause.BATCH_SIZE),
                arguments.getAllValues().stream().map(batch -> batch.length).toList());
        assertEquals(LongStream.rangeClosed(1, 1001).boxed().toList(), arguments.getAllValues().stream()
                .flatMap(java.util.Arrays::stream).toList());
        for (int index = 0; index < sql.getAllValues().size(); index++) {
            assertEquals(arguments.getAllValues().get(index).length,
                    sql.getAllValues().get(index).chars().filter(character -> character == '?').count());
        }
        verifyNoInteractions(json);
    }
}
