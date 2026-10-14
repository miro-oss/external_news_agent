package com.example.be.domain.dart.company;

import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DartCompanyServiceTest {
    private final DartCompanyRepository repository = mock(DartCompanyRepository.class);
    private final DartProperties properties = new DartProperties();
    private final DartCompanyService service = new DartCompanyService(repository, properties);

    @Test void keylessSearchDoesNotTouchDatabaseButRetainsExistingSelectionValidation() {
        assertEquals(DartCompanyService.Status.DISABLED, service.search("삼성", 0, 20).status());
        verifyNoInteractions(repository);
        when(repository.find("00126380")).thenReturn(Optional.of(DartCompanyTestFixtures.COMPANY));
        assertEquals(DartCompanyTestFixtures.COMPANY, service.requireCompany("00126380"));
    }

    @Test void preparationAndFailureAreExplicitWhileLastSuccessfulCorpusRemainsSearchable() {
        properties.setApiKey("synthetic-key");
        assertEquals(DartCompanyService.Status.PREPARING, service.search("삼성", 0, 20).status());
        service.syncFinished(false);
        assertEquals(DartCompanyService.Status.FAILED, service.search("삼성", 0, 20).status());
        when(repository.hasCompanies()).thenReturn(true);
        when(repository.count("삼성")).thenReturn(1L);
        when(repository.search("삼성", 0, 20)).thenReturn(List.of(DartCompanyTestFixtures.COMPANY));
        var result = service.search("  삼성  ", 0, 20);
        assertEquals(DartCompanyService.Status.READY, result.status());
        assertEquals(1L, result.totalElements());
        assertEquals(1, result.totalPages());
        assertFalse(result.hasNext());
    }

    @Test void validatesSearchLimitsEvenWhenDisabledAndDoesNotTreatWildcardsAsQuerySyntax() {
        assertEquals("query는 2자 이상 100자 이하여야 합니다.",
                assertThrows(GeneralException.class, () -> service.search("  a ", 0, 20)).getMessage());
        assertEquals("page는 0 이상이어야 합니다.",
                assertThrows(GeneralException.class, () -> service.search("삼성", -1, 20)).getMessage());
        assertEquals("size는 1 이상 100 이하여야 합니다.",
                assertThrows(GeneralException.class, () -> service.search("삼성", 0, 101)).getMessage());
        properties.setApiKey("synthetic-key");
        when(repository.hasCompanies()).thenReturn(true);
        when(repository.search("%_", 0, 20)).thenReturn(List.of());
        assertTrue(service.search("%_", 0, 20).content().isEmpty());
        verify(repository).count("%_");
        verify(repository).search("%_", 0, 20);
    }

    @Test void invalidCompanyHasNotionTopic400AndDoesNotChangeAnyState() {
        var error = assertThrows(TopicException.class, () -> service.requireCompany("not-a-code"));
        assertEquals("TOPIC400", error.getCode().getCode());
        assertEquals("선택한 공시 회사를 찾을 수 없습니다.", error.getMessage());
        verifyNoInteractions(repository);
    }
}
