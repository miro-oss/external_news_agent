package com.example.be.domain.sources.service.command;

import com.example.be.domain.sources.dto.req.SourceReqDTO;
import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.exception.SourceException;
import com.example.be.domain.sources.exception.code.SourceErrorCode;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceCommandServiceImplTest {

    @Mock
    private SourceRepository sourceRepository;

    @InjectMocks
    private SourceCommandServiceImpl sourceCommandService;

    @Test
    void createSourceAcceptsProviderKeyAndStartsWithUnknownRobotsStatus() {
        SourceReqDTO.Create request = searchRequest("naver");
        when(sourceRepository.existsBySourceKindAndUrlTemplate(Source.KIND_SEARCH, "NAVER")).thenReturn(false);
        when(sourceRepository.saveAndFlush(any(Source.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SourceResDTO.Created result = sourceCommandService.createSource(request);

        assertEquals("SEARCH", result.getSourceKind());
        assertEquals("NAVER", result.getUrlTemplate());
        assertEquals(Source.ROBOTS_STATUS_UNKNOWN, result.getRobotsStatus());
        assertNull(result.getRobotsCheckedAt());
        assertTrue(result.isActive());
    }

    @Test
    void createSourceAcceptsSearchUrlTemplateWithQueryPlaceholder() {
        SourceReqDTO.Create request = searchRequest("https://news.google.com/rss/search?q={query}&hl=ko");
        when(sourceRepository.existsBySourceKindAndUrlTemplate(any(), anyString())).thenReturn(false);
        when(sourceRepository.saveAndFlush(any(Source.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SourceResDTO.Created result = sourceCommandService.createSource(request);

        assertEquals("https://news.google.com/rss/search?q={query}&hl=ko", result.getUrlTemplate());
    }

    @Test
    void createSourceRejectsSearchTemplateThatIsNeitherPlaceholderNorProviderKey() {
        SourceReqDTO.Create request = searchRequest("네이버");

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals(SourceErrorCode.INVALID_SEARCH_URL_TEMPLATE, exception.getCode());
        verify(sourceRepository, never()).saveAndFlush(any(Source.class));
    }

    @Test
    void createSourceRejectsSearchPlaceholderWithoutHttpScheme() {
        SourceReqDTO.Create request = searchRequest("news.google.com/rss/search?q={query}");

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals(SourceErrorCode.INVALID_SEARCH_URL_TEMPLATE, exception.getCode());
    }

    @Test
    void createSourceRejectsFeedTemplateThatIsNotHttpUrl() {
        SourceReqDTO.Create request = feedRequest("rss.etnews.com/Section902.xml");

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals(SourceErrorCode.INVALID_FEED_URL_TEMPLATE, exception.getCode());
    }

    @Test
    void createSourceRejectsUnknownSourceKind() {
        SourceReqDTO.Create request = feedRequest("https://rss.etnews.com/Section902.xml");
        request.setSourceKind("RSS");

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals(SourceErrorCode.INVALID_SOURCE_KIND, exception.getCode());
    }

    @Test
    void createSourceRejectsBlankName() {
        SourceReqDTO.Create request = feedRequest("https://rss.etnews.com/Section902.xml");
        request.setName("  ");

        GeneralException exception = assertThrows(GeneralException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals("COMMON400", exception.getCode().getCode());
        assertEquals("name은 필수입니다.", exception.getMessage());
    }

    @Test
    void createSourceRejectsOutOfRangeReliabilityScore() {
        SourceReqDTO.Create request = feedRequest("https://rss.etnews.com/Section902.xml");
        request.setReliabilityScore(new BigDecimal("1.5"));

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals(SourceErrorCode.INVALID_RELIABILITY_SCORE, exception.getCode());
    }

    @Test
    void createSourceRejectsDuplicatedKindAndUrl() {
        SourceReqDTO.Create request = feedRequest("https://rss.etnews.com/Section902.xml");
        when(sourceRepository.existsBySourceKindAndUrlTemplate(Source.KIND_FEED,
                "https://rss.etnews.com/Section902.xml")).thenReturn(true);

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals(SourceErrorCode.DUPLICATED_SOURCE, exception.getCode());
        verify(sourceRepository, never()).saveAndFlush(any(Source.class));
    }

    @Test
    void createSourceTranslatesUniqueViolationToConflict() {
        SourceReqDTO.Create request = feedRequest("https://rss.etnews.com/Section902.xml");
        when(sourceRepository.existsBySourceKindAndUrlTemplate(any(), anyString())).thenReturn(false);
        when(sourceRepository.saveAndFlush(any(Source.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("ORA-00001: unique constraint (NEWS_AGENT.UQ_NEWS_SOURCE) violated")));

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.createSource(request));

        assertEquals(SourceErrorCode.DUPLICATED_SOURCE, exception.getCode());
    }

    @Test
    void createSourcePropagatesUnrelatedIntegrityViolation() {
        SourceReqDTO.Create request = feedRequest("https://rss.etnews.com/Section902.xml");
        when(sourceRepository.existsBySourceKindAndUrlTemplate(any(), anyString())).thenReturn(false);
        when(sourceRepository.saveAndFlush(any(Source.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("ORA-02290: check constraint (NEWS_AGENT.CK_SOURCE_POLICY) violated")));

        assertThrows(DataIntegrityViolationException.class, () -> sourceCommandService.createSource(request));
    }

    @Test
    void updateSourceKeepsUntouchedFields() {
        SourceReqDTO.Update request = new SourceReqDTO.Update();
        request.setName("ETNews 반도체 (개편)");
        request.setCrawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 20, false));
        request.setActive(false);

        Source source = existingFeedSource();
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));

        SourceResDTO.Updated result = sourceCommandService.updateSource(1L, request);

        assertEquals("ETNews 반도체 (개편)", source.getName());
        assertEquals("https://rss.etnews.com/Section902.xml", source.getUrlTemplate());
        assertEquals("ko", source.getLanguage());
        assertEquals(new BigDecimal("0.85"), source.getReliabilityScore());
        assertEquals(20, source.getCrawlPolicy().maxArticlesPerRun());
        assertFalse(source.isActive());
        assertEquals("ETNews 반도체 (개편)", result.getName());
    }

    @Test
    void updateSourceDoesNotTouchRobotsFields() {
        SourceReqDTO.Update request = new SourceReqDTO.Update();
        request.setName("이름만 변경");
        Source source = existingFeedSource();
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));

        SourceResDTO.Updated result = sourceCommandService.updateSource(1L, request);

        assertEquals(Source.ROBOTS_STATUS_ALLOWED, source.getRobotsStatus());
        assertEquals(Source.ROBOTS_STATUS_ALLOWED, result.getRobotsStatus());
    }

    @Test
    void updateSourceSkipsDuplicateCheckWhenUrlIsUnchanged() {
        SourceReqDTO.Update request = new SourceReqDTO.Update();
        request.setUrlTemplate("https://rss.etnews.com/Section902.xml");
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(existingFeedSource()));

        sourceCommandService.updateSource(1L, request);

        verify(sourceRepository, never()).existsBySourceKindAndUrlTemplateAndIdNot(anyString(), anyString(), anyLong());
    }

    @Test
    void updateSourceRejectsUrlAlreadyUsedByAnotherSource() {
        SourceReqDTO.Update request = new SourceReqDTO.Update();
        request.setUrlTemplate("https://rss.etnews.com/Section903.xml");
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(existingFeedSource()));
        when(sourceRepository.existsBySourceKindAndUrlTemplateAndIdNot(
                Source.KIND_FEED, "https://rss.etnews.com/Section903.xml", 1L)).thenReturn(true);

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.updateSource(1L, request));

        assertEquals(SourceErrorCode.DUPLICATED_SOURCE, exception.getCode());
    }

    @Test
    void updateSourceRejectsMissingSource() {
        when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.updateSource(99L, new SourceReqDTO.Update()));

        assertEquals(SourceErrorCode.SOURCE_NOT_FOUND, exception.getCode());
    }

    @Test
    void deleteSourceDeactivatesInsteadOfRemovingRecord() {
        Source source = existingFeedSource();
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));

        SourceResDTO.Deleted result = sourceCommandService.deleteSource(1L);

        assertEquals(1L, result.getId());
        assertFalse(result.isActive());
        assertFalse(source.isActive());
        verify(sourceRepository, never()).delete(any(Source.class));
    }

    @Test
    void deleteSourceRejectsSourceLinkedToTopics() {
        Source source = Source.builder()
                .id(1L)
                .sourceKind(Source.KIND_FEED)
                .name("ETNews 반도체")
                .urlTemplate("https://rss.etnews.com/Section902.xml")
                .active(true)
                .topics(List.of(topic(1L, "HBM"), topic(2L, "DRAM")))
                .build();
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(source));

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.deleteSource(1L));

        assertEquals(SourceErrorCode.SOURCE_LINKED_TO_TOPIC, exception.getCode());
        assertEquals(List.of(1L, 2L), exception.getResult().get("linkedTopicIds"));
        assertTrue(source.isActive());
    }

    @Test
    void deleteSourceRejectsMissingSource() {
        when(sourceRepository.findById(99L)).thenReturn(Optional.empty());

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.deleteSource(99L));

        assertEquals(SourceErrorCode.SOURCE_NOT_FOUND, exception.getCode());
    }

    private SourceReqDTO.Create feedRequest(String urlTemplate) {
        SourceReqDTO.Create request = new SourceReqDTO.Create();
        request.setSourceKind(Source.KIND_FEED);
        request.setName("ETNews 반도체");
        request.setUrlTemplate(urlTemplate);
        request.setCountry("kr");
        request.setLanguage("ko");
        return request;
    }

    private SourceReqDTO.Create searchRequest(String urlTemplate) {
        SourceReqDTO.Create request = new SourceReqDTO.Create();
        request.setSourceKind(Source.KIND_SEARCH);
        request.setName("Naver 뉴스 검색");
        request.setUrlTemplate(urlTemplate);
        request.setCountry("KR");
        request.setLanguage("ko");
        request.setCrawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 50, true));
        request.setReliabilityScore(new BigDecimal("0.9"));
        return request;
    }

    private Source existingFeedSource() {
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
