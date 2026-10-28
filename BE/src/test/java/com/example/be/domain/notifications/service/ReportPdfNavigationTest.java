package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportPdfNavigationTest {
    @Test
    void offlineCompanyIndexHasResolvedPageNumbersAndRoundTripsToSummary() throws Exception {
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).title("가상 뉴스 브리핑").build();
        var dart = mock(ReportDartService.class);
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of(
                company("00000001", "가상 기업 하나"), company("00000002", "긴 회사명 ".repeat(30)))));
        var content = new ReportContent(List.of("핵심 내용을 먼저 확인합니다."), List.of(), List.of(), List.of());
        var view = new ReportEventFeedbackProjection.View(List.of(), new ReportReadingContent("", content), false);
        var result = new ReportPdfRenderer(dart).renderDetailed(report, view, null);
        try (var document = Loader.loadPDF(result.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.indexOf("핵심 내용을 먼저") < text.indexOf("회사별 부록 바로가기"));
            assertTrue(text.indexOf("회사별 부록 바로가기") < text.indexOf("회사 실적 · 공시"));
            assertEquals(Set.of("00000001", "00000002"), result.companyPages().keySet());
            assertTrue(result.companyPages().get("00000002") > result.companyPages().get("00000001"));
            var destinations = document.getDocumentCatalog().getNames().getDests().getNames();
            assertEquals(Set.of("summary", "report-detail", "company-0", "company-1"), destinations.keySet());
            int links = 0;
            for (var page : document.getPages()) for (var annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionGoTo action) {
                    String name = ((PDNamedDestination) action.getDestination()).getNamedDestination();
                    assertNotNull(destinations.get(name));
                    assertTrue(destinations.get(name).retrievePageNumber() >= 0);
                    assertTrue(link.getRectangle().getLowerLeftY() >= 50);
                    links++;
                }
            }
            assertTrue(links >= 4);
            result.companyPages().values().forEach(page -> assertTrue(text.contains("p. " + page)));
            assertTrue(text.contains("핵심 요약으로 돌아가기"));
            assertTrue(text.contains("표시할 저장된 실적·공시 자료가 없습니다."));
        }
        verify(dart).getStored(report);
        verifyNoMoreInteractions(dart);
    }

    @Test
    void excludedCompanyNeverReturnsInIndexDestinationOrPageMap() throws Exception {
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).title("허용된 보고서").build();
        var dart = mock(ReportDartService.class);
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of(company("00000001", "숨긴 기업"))));
        var view = new ReportEventFeedbackProjection.View(List.of(),
                new ReportReadingContent("", new ReportContent(List.of(), List.of(), List.of(), List.of())), true, Set.of(1L));
        var result = new ReportPdfRenderer(dart).renderDetailed(report, view, null);
        assertTrue(result.companyPages().isEmpty());
        try (var document = Loader.loadPDF(result.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("숨긴 기업"));
            assertFalse(text.contains("회사별 부록"));
            assertEquals(Set.of("summary", "report-detail"), document.getDocumentCatalog().getNames().getDests().getNames().keySet());
        }
    }

    private ReportDartDTO.CompanyAttachment company(String code, String name) {
        return new ReportDartDTO.CompanyAttachment(new Company(code, name, null), "PENDING", null, null);
    }
}
