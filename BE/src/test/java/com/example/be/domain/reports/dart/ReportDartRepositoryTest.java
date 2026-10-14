package com.example.be.domain.reports.dart;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportDartRepositoryTest {
    @Test void cachedSnapshotPreservesOriginalOffsetAndExactAmountsAcrossJsonPersistence() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var mapper = new ObjectMapper();
        var row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(mapper.writeValueAsString(data()));
        when(row.getTimestamp(2)).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 10, 14, 10, 0)));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(SAMSUNG.corpCode())))
                .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)));

        var restored = new ReportDartRepository(jdbc, mapper).cached(SAMSUNG.corpCode()).orElseThrow().data();
        assertEquals(data(), restored);
        assertEquals("+09:00", restored.fetchedAt().getOffset().toString());
        assertEquals("10000000000000001", restored.quarters().getFirst().revenue());
    }
}
