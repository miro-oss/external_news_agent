package com.example.be.domain.feedback.repository;

import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
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
    void reviewStatesReadNoClobsOrFrozenInput() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(8L);
        when(row.getLong("report_id")).thenReturn(17L);
        when(row.getString("category")).thenReturn("SUMMARY_ERROR");
        when(row.getString("status")).thenReturn("COMPLETED");
        when(row.getString("verdict")).thenReturn("CONFIRMED_ERROR");
        when(row.getString("event_key")).thenReturn("a".repeat(64));
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 12, 0)));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Feedback>>any(), any(Object[].class)))
                .thenAnswer(call -> List.of(call.<RowMapper<Feedback>>getArgument(1).mapRow(row, 0)));

        var feedback = store.eventReviewStates(List.of(17L)).getFirst();

        assertEquals(17L, feedback.reportId());
        assertEquals("CONFIRMED_ERROR", feedback.verdict());
        assertEquals("a".repeat(64), feedback.eventKey());
        assertNull(feedback.input());
        assertNull(feedback.diagnosis());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<Feedback>>any(), any(Object[].class));
        assertFalse(sql.getValue().contains("SELECT *"));
        assertFalse(sql.getValue().contains("input_json"));
        assertFalse(sql.getValue().contains("review_json"));
        assertFalse(sql.getValue().contains("diagnosis"));
        verify(row, never()).getString("input_json");
        verify(row, never()).getString("review_json");
        verify(row, never()).getString("diagnosis");
        verifyNoInteractions(json);
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
