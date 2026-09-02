package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.entity.NewsInsight;
import com.example.be.domain.insights.repository.NewsInsightRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightPersistenceServiceTest {

    @Test
    void storesProductEvidenceIndexesAsZeroBased() {
        NewsInsightRepository repository = mock(NewsInsightRepository.class);
        InsightPersistenceService service = new InsightPersistenceService(repository);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentInsightResponse response = new AgentInsightResponse(
                List.of(new AgentInsightResponse.Insight(
                        "CHIP_MAKER",
                        "양산 일정 변화",
                        List.of(new AgentInsightResponse.Fact(
                                "FACT", "f1", "확인된 사실", 501L,
                                List.of(1, 3), "grounded", "원문 확인")),
                        List.of(new AgentInsightResponse.Implication(
                                "IMPLICATION", "i1", "점검 필요", List.of("f1"),
                                "일정 유지", "일정 번복")),
                        List.of("후속 발표"),
                        new BigDecimal("0.8"))),
                new AgentInsightResponse.Meta(
                        "gemini", "gemini-test", "insight.ko.v1+perspective.ko.v1",
                        20L, 10L, new BigDecimal("0.1"), BigDecimal.ONE, false, false));

        service.saveGenerated(AgentTargetType.ISSUE, 88L, "a".repeat(64), response);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewsInsight>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        NewsInsight saved = captor.getValue().getFirst();
        assertEquals(Audience.CHIP_MAKER, saved.getAudience());
        assertEquals(List.of(0, 2), saved.getFacts().getFirst().evidenceSentenceIds());
        assertEquals("일정 번복", saved.getImplications().getFirst().falsifiedBy());
    }
}
