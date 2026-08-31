package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IssueCrossSourceWriterTest {

    private final NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueCrossSourceWriter writer =
            new IssueCrossSourceWriter(issueRepository, issueArticleRepository);

    @Test
    void storesRuleStancesAndMarksOnlyRealPromotedAnalysisAsLlm() {
        NewsIssue issue = NewsIssue.builder().id(88L).crossSource(IssueCrossSource.empty()).build();
        IssueArticle representative = membership(
                1L, issue, Article.builder().id(10L).build(), IssueArticleRole.REPRESENTATIVE);
        IssueArticle member = membership(
                2L, issue, Article.builder().id(11L).build(), IssueArticleRole.MEMBER);
        IssueCrossSource crossSource = new IssueCrossSource(
                List.of(),
                List.of(),
                List.of(new IssueCrossSource.Conflict(
                        List.of(10L, 11L), "양산 일정에 대한 보도가 충돌합니다.")),
                List.of());
        when(issueRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(representative, member));
        when(issueArticleRepository.findByIssueIdAndArticleId(88L, 11L))
                .thenReturn(Optional.of(member));

        writer.applyRepresentative(
                88L,
                crossSource,
                List.of(new IssueCrossSourceWriter.RuleStance(
                        11L, IssueStance.DISPUTES, new BigDecimal("0.850"))),
                true);

        assertEquals(crossSource, issue.getCrossSource());
        assertEquals(IssueStanceSource.LLM, representative.getStanceSource());
        assertEquals(IssueStance.DISPUTES, member.getStance());
        assertEquals(IssueStanceSource.RULE, member.getStanceSource());

        writer.confirmPromotion(
                88L, 11L, IssueStance.DISPUTES, new BigDecimal("0.850"), true);

        assertEquals(IssueStanceSource.LLM, member.getStanceSource());
        assertEquals(new BigDecimal("0.850"), member.getStanceConfidence());
    }

    private IssueArticle membership(
            Long id,
            NewsIssue issue,
            Article article,
            IssueArticleRole role) {
        return IssueArticle.builder()
                .id(id)
                .issue(issue)
                .article(article)
                .role(role)
                .stance(IssueStance.SUPPORTS)
                .stanceSource(IssueStanceSource.RULE)
                .stanceConfidence(new BigDecimal("0.500"))
                .build();
    }
}
