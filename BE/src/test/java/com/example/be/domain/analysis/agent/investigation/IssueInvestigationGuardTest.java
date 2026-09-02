package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.dto.AgentExploreRequest;
import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IssueInvestigationGuardTest {

    private final AgentRunJdbcRepository repository = mock(AgentRunJdbcRepository.class);
    private final IssueInvestigationGuard guard = new IssueInvestigationGuard(repository);

    @Test
    void rejectsNormalizedDuplicateQueryInSameRun() {
        String queryHash = InvestigationQueryNormalizer.hash("삼성전자는 HBM 투자");
        when(repository.existsInvestigationQueryHash(42L, queryHash)).thenReturn(true);

        IssueInvestigationGuard.Decision decision = guard.evaluate(
                42L,
                context(),
                proposal("SEARCH_MORE", "NAVER", "삼성전자는 HBM 투자", null, List.of(), null));

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReason().contains("이미 수행"));
    }

    @Test
    void rejectsSourceOutsideSpringWhitelist() {
        IssueInvestigationGuard.Decision decision = guard.evaluate(
                42L,
                context(),
                proposal("SEARCH_MORE", "TAVILY", "HBM 투자", null, List.of(), null));

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReason().contains("허용 소스"));
    }

    @Test
    void acceptsOnlyMetadataArticleBelongingToIssue() {
        IssueInvestigationGuard.Decision accepted = guard.evaluate(
                42L,
                context(),
                proposal("READ_FULLTEXT", null, null, 101L, List.of(), null));
        IssueInvestigationGuard.Decision rejected = guard.evaluate(
                42L,
                context(),
                proposal("READ_FULLTEXT", null, null, 999L, List.of(), null));

        assertTrue(accepted.accepted());
        assertFalse(rejected.accepted());
    }

    @Test
    void rejectsMissingLookupKeysInsteadOfThrowing() {
        IssueInvestigationGuard.Decision missingArticle = guard.evaluate(
                42L,
                context(),
                proposal("READ_FULLTEXT", null, null, null, List.of(), null));
        IssueInvestigationGuard.Decision missingSource = guard.evaluate(
                42L,
                context(),
                proposal("SEARCH_MORE", null, "HBM 투자", null, List.of(), null));

        assertFalse(missingArticle.accepted());
        assertFalse(missingSource.accepted());
    }

    @Test
    void acceptsHistoryEntityWithEquivalentCaseAndSpacing() {
        IssueInvestigationGuard.Decision decision = guard.evaluate(
                42L,
                context(),
                proposal("COMPARE_HISTORY", null, null, null, List.of("sk 하이닉스"), 30));

        assertTrue(decision.accepted());
    }

    private InvestigationContext context() {
        return new InvestigationContext(
                88L, 7L, "HBM 투자", "투자 검토", "DISPUTED",
                new BigDecimal("90"), new BigDecimal("70"),
                List.of("SK하이닉스"), List.of(), 2, 5,
                List.of(101L), List.of(101L),
                List.of(new AgentExploreRequest.AllowedSource("NAVER", "네이버", "SEARCH")),
                Map.of("NAVER", 11L), false, "상충 보도 상태");
    }

    private AgentExploreResponse.Proposal proposal(String action,
                                                   String sourceKey,
                                                   String query,
                                                   Long articleId,
                                                   List<String> entities,
                                                   Integer days) {
        return new AgentExploreResponse.Proposal(
                action, sourceKey, query, articleId, entities, days, "조사 이유");
    }
}
