package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportPdfNavigationTest {
    @Test
    void compactCompanyAppendixSharesPagesAndDoesNotAddCompanyNavigation() throws Exception {
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN)
                .collectionContexts(List.of(new com.example.be.domain.reports.entity.ReportCollectionContext(42L,
                        List.of(new com.example.be.domain.collection.entity.CollectionTopicSnapshot(29L, "첫 회사", null,
                                        List.of(), List.of(), List.of(), 10, 60, new Company("00000001", "첫 회사", "000001")),
                                new com.example.be.domain.collection.entity.CollectionTopicSnapshot(30L, "둘째 회사", null,
                                        List.of(), List.of(), List.of(), 10, 60, new Company("00000002", "둘째 회사", "000002"))))))
                .title("가상 뉴스 브리핑").build();
        var dart = mock(ReportDartService.class);
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of(
                company("00000001", "가상 기업 하나"), company("00000002", "가상 기업 둘"))));
        var content = new ReportContent(List.of(), List.of(new ReportContent.ImportantEvent(
                "가상 기업 하나·가상 기업 둘 공동 발표", "본문 뉴스의 마지막 내용", "", List.of(1L))), List.of(), List.of());
        var view = new ReportEventFeedbackProjection.View(List.of(Finding.builder().id(1L)
                .article(Article.builder().id(101L).build()).build()), new ReportReadingContent("", content), false);
        var result = new ReportPdfRenderer(dart).renderDetailed(report, view, null);
        try (var document = Loader.loadPDF(result.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.indexOf("본문 뉴스의 마지막 내용") < text.indexOf("회사 실적 · 공시"));
            assertFalse(text.contains("회사별 부록 바로가기"));
            assertEquals(Set.of("00000001", "00000002"), result.companyPages().keySet());
            assertEquals(result.companyPages().get("00000001"), result.companyPages().get("00000002"));
            var destinations = document.getDocumentCatalog().getNames().getDests().getNames();
            assertEquals(Set.of("summary", "report-detail"), destinations.keySet());
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
            assertEquals(2, links);
            assertTrue(text.contains("핵심 브리핑으로 돌아가기"));
            assertFalse(text.contains("핵심 요약"));
            assertTrue(text.contains("공시 자료 준비 중"));
        }
        verify(dart).getStored(report);
        verifyNoMoreInteractions(dart);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void twoOrdinaryFinancialCompaniesShareAnAppendixPageWithTheirCompleteTables(int quarterCount) throws Exception {
        var companies = List.of(new Company("00000001", "가상 기업 하나", "000001"),
                new Company("00000002", "가상 기업 둘", "000002"));
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).title("두 회사의 간단 부록")
                .collectionContexts(List.of(new com.example.be.domain.reports.entity.ReportCollectionContext(42L,
                        java.util.stream.IntStream.range(0, 2).mapToObj(index ->
                                new com.example.be.domain.collection.entity.CollectionTopicSnapshot((long) index + 1,
                                        "회사 주제", null, List.of(), List.of(), List.of(), 10, 60, companies.get(index))).toList())))
                .build();
        var quarters = java.util.stream.IntStream.rangeClosed(1, quarterCount).mapToObj(index ->
                new Quarter(2026, index, "10000000000", "-1000000000", null, index == 4, List.of())).toList();
        var data = new FinancialData("CFS", "KRW", java.time.OffsetDateTime.parse("2026-10-15T09:00:00+09:00"),
                quarters, List.of(), List.of());
        var dart = mock(ReportDartService.class);
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, companies.stream().map(company ->
                new ReportDartDTO.CompanyAttachment(company, "READY", null, data)).toList()));
        // Leave only a small news-page tail, so both complete financial blocks start on the appendix page.
        String news = "뉴스 상세 내용은 회사 부록 앞에 둡니다.\n".repeat(30);
        var content = new ReportContent(List.of(), List.of(new ReportContent.ImportantEvent(
                "가상 기업 하나·가상 기업 둘 공동 발표", news, "", List.of(1L))), List.of(), List.of());
        var view = new ReportEventFeedbackProjection.View(List.of(Finding.builder().id(1L)
                .article(Article.builder().id(101L).build()).build()), new ReportReadingContent("", content), false);
        var result = new ReportPdfRenderer(dart).renderDetailed(report, view, null);
        int appendixPage = result.companyPages().get("00000001");
        assertEquals(appendixPage, result.companyPages().get("00000002"));
        try (var document = Loader.loadPDF(result.bytes())) {
            assertEquals(appendixPage, document.getNumberOfPages(), "short notes must not create a separate appendix tail page");
            var stripper = new PDFTextStripper();
            stripper.setStartPage(appendixPage);
            stripper.setEndPage(appendixPage);
            String text = stripper.getText(document);
            assertTrue(text.contains("가상 기업 하나 · 000001"));
            assertTrue(text.contains("가상 기업 둘 · 000002"));
            assertEquals(2, text.split("최근 " + quarterCount + "분기", -1).length - 1);
            assertEquals(2, text.split("2026년 " + quarterCount + "분기", -1).length - 1);
            assertEquals(1, text.split("자료 없음은 0이 아닙니다", -1).length - 1);
        }
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
        return new ReportDartDTO.CompanyAttachment(new Company(code, name, code.substring(2)), "PENDING", null, null);
    }
}
