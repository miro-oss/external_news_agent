package com.example.be.domain.dart.financial;

import com.example.be.domain.dart.client.DartClient;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.dart.exception.DartUpstreamException;
import com.example.be.domain.dart.model.DartModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.example.be.domain.dart.financial.DartFinancialTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DartFinancialServiceTest {
    private final DartClient client = mock(DartClient.class);
    private final DartProperties properties = new DartProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.ofHours(9));
    private final DartFinancialService service = new DartFinancialService(client, properties, clock);
    private final DartModels.Filing q2 = filing("20260814000001", "반기보고서 (2026.06)");
    private final DartModels.Filing q1 = filing("20260514000001", "분기보고서 (2026.03)");

    @BeforeEach void setup() {
        properties.setApiKey("synthetic-key");
        when(client.company(CORP)).thenReturn(JSON.readTree("{\"status\":\"000\",\"corp_code\":\"00126380\",\"acc_mt\":\"12\"}"));
        when(client.filings(eq(CORP), any(), any(), eq(false), eq(1), eq(5))).thenReturn(filingList(List.of(q2)));
        when(client.filings(eq(CORP), any(), any(), eq(true), eq(1), eq(100))).thenReturn(filingList(List.of(q2, q1)));
        when(client.financial(eq(CORP), anyInt(), anyString(), anyString())).thenReturn(noData());
    }

    @Test void actualPublishedReportAnchors12ContinuousQuartersInsteadOfCalendarQuarter() {
        var period = new DartFinancialNormalizer.Period(2026, 2);
        when(client.financial(CORP, 2026, "11012", "CFS"))
                .thenReturn(financial(period, q2.rceptNo(), "200", "300", "20", "30"));
        var data = service.fetch(COMPANY);
        assertEquals(12, data.quarters().size());
        assertEquals(2023, data.quarters().getFirst().year());
        assertEquals(3, data.quarters().getFirst().quarter());
        assertEquals(2, data.quarters().getLast().quarter());
        assertNull(data.quarters().get(10).revenue());
        assertEquals("200", data.quarters().getLast().revenue());
        assertEquals("CFS", data.fsDiv());
        verify(client, never()).financial(anyString(), anyInt(), anyString(), eq("OFS"));
        verify(client, never()).financial(CORP, 2026, "11014", "CFS");
    }

    @Test void missingConsolidatedHistorySwitchesWholeSeriesToSeparate() {
        when(client.financial(CORP, 2026, "11012", "OFS")).thenReturn(
                financial(new DartFinancialNormalizer.Period(2026, 2), q2.rceptNo(), "200", "300", "20", "30"));
        var data = service.fetch(COMPANY);
        assertEquals("OFS", data.fsDiv());
        assertEquals("200", data.quarters().getLast().revenue());
        assertTrue(data.notes().stream().anyMatch(note -> note.contains("별도재무제표")));
        verify(client).financial(CORP, 2026, "11013", "OFS");
    }

    @Test void unsupportedFiscalYearReturnsDisclosuresAndExplanationWithoutGuessingQuarterAmounts() {
        when(client.company(CORP)).thenReturn(JSON.readTree("{\"status\":\"000\",\"corp_code\":\"00126380\",\"acc_mt\":\"03\"}"));
        var data = service.fetch(COMPANY);
        assertTrue(data.quarters().isEmpty());
        assertEquals(1, data.disclosures().size());
        assertTrue(data.notes().getFirst().contains("회계기간"));
        verify(client, never()).financial(anyString(), anyInt(), anyString(), anyString());
    }

    @Test void entirelyMissingFinancialsKeepsCalendarGapsAndDoesNotInventCurrencyOrBasis() {
        var data = service.fetch(COMPANY);
        assertEquals(12, data.quarters().size());
        assertTrue(data.quarters().stream().allMatch(quarter -> quarter.revenue() == null));
        assertNull(data.fsDiv());
        assertNull(data.currency());
        assertFalse(data.notes().isEmpty());
    }

    @Test void disabledServiceMakesNoExternalRequests() {
        reset(client);
        properties.setApiKey("");
        assertEquals("DISABLED", assertThrows(DartUpstreamException.class, () -> service.fetch(COMPANY)).getStatus());
        verifyNoInteractions(client);
    }

    @Test void rejectsImpossibleOrNonDecemberFiscalReportingPeriods() {
        assertEquals(new DartFinancialNormalizer.Period(2025, 4), DartFinancialService.period("[기재정정]사업보고서 (2025.12)"));
        assertNull(DartFinancialService.period("사업보고서 (2025.03)"));
        assertNull(DartFinancialService.period("반기보고서 (2025.09)"));
        assertNull(DartFinancialService.period("분기보고서 (2025.06)"));
    }

    @Test void overallDeadlineStopsBeforeNextRequestToStayWithinWorkerLease() {
        var instant = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-14T00:00:00Z"));
        Clock movingClock = mock(Clock.class);
        when(movingClock.getZone()).thenReturn(ZoneOffset.ofHours(9));
        when(movingClock.instant()).thenAnswer(call -> instant.get());
        when(client.filings(eq(CORP), any(), any(), eq(false), eq(1), eq(5))).thenAnswer(call -> {
            instant.set(instant.get().plusSeconds(16 * 60));
            return filingList(List.of(q2));
        });
        var bounded = new DartFinancialService(client, properties, movingClock);
        assertEquals("TIMEOUT", assertThrows(DartUpstreamException.class, () -> bounded.fetch(COMPANY)).getStatus());
        verify(client, never()).company(anyString());
        verify(client, never()).financial(anyString(), anyInt(), anyString(), anyString());
    }

    @Test void todaysDecemberProfileCannotOverrideHistoricalFiscalYearChange() {
        var oldCalendar = filing("20250714000001", "사업보고서 (2025.03)");
        when(client.filings(eq(CORP), any(), any(), eq(true), eq(1), eq(100)))
                .thenReturn(filingList(List.of(q2, q1, oldCalendar)));
        var data = service.fetch(COMPANY);
        assertTrue(data.quarters().isEmpty());
        assertTrue(data.notes().getFirst().contains("회계기간 변경"));
        assertEquals(1, data.disclosures().size());
        verify(client, never()).financial(anyString(), anyInt(), anyString(), anyString());
    }

    @Test void fiscalChangeBeforeVisibleTwelveQuartersDoesNotHideTheCurrentSeries() {
        var oldCalendar = filing("20230614000001", "사업보고서 (2023.03)");
        when(client.filings(eq(CORP), any(), any(), eq(true), eq(1), eq(100)))
                .thenReturn(filingList(List.of(q2, q1, oldCalendar)));
        var data = service.fetch(COMPANY);
        assertEquals(12, data.quarters().size());
        assertEquals(2023, data.quarters().getFirst().year());
        assertEquals(3, data.quarters().getFirst().quarter());
        assertTrue(data.notes().stream().noneMatch(note -> note.contains("회계기간 변경")));
    }
}
