package com.example.be.domain.dart;

import com.example.be.global.apiPayload.exception.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DartCompanyControllerTest {
    private MockMvc mvc;
    private final DartCompanyRepository repository = mock(DartCompanyRepository.class);
    private final DartProperties properties = new DartProperties();

    @BeforeEach void setup() {
        var service = new DartCompanyService(repository, properties);
        mvc = MockMvcBuilders.standaloneSetup(new DartCompanyController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void exposesNotionEnvelopeAndDisabledStateWithoutCredentials() throws Exception {
        mvc.perform(get("/api/news/dart/companies").param("query", "삼성"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("성공입니다."))
                .andExpect(jsonPath("$.result.status").value("DISABLED"))
                .andExpect(jsonPath("$.result.message").value("DART API 키가 설정되지 않았습니다."))
                .andExpect(jsonPath("$.result.content").isEmpty()).andExpect(jsonPath("$.result.size").value(20));
    }

    @Test void queryIsRequiredAndValidationUsesExactNotionMessages() throws Exception {
        mvc.perform(get("/api/news/dart/companies"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.message").value("query는 2자 이상 100자 이하여야 합니다."))
                .andExpect(jsonPath("$.result").isEmpty());
        mvc.perform(get("/api/news/dart/companies").param("query", "삼성").param("page", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("page는 0 이상이어야 합니다."));
        mvc.perform(get("/api/news/dart/companies").param("query", "삼성").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("size는 1 이상 100 이하여야 합니다."));
    }

    @Test void retainsIdentifierStringsAndPaginationFields() throws Exception {
        properties.setApiKey("synthetic-key");
        when(repository.hasCompanies()).thenReturn(true);
        when(repository.count("005930")).thenReturn(1L);
        when(repository.search("005930", 0, 20)).thenReturn(List.of(DartTestFixtures.COMPANY));
        mvc.perform(get("/api/news/dart/companies").param("query", "005930"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].corpCode").value("00126380"))
                .andExpect(jsonPath("$.result.content[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.result.totalElements").value(1)).andExpect(jsonPath("$.result.totalPages").value(1))
                .andExpect(jsonPath("$.result.hasNext").value(false));
    }
}
