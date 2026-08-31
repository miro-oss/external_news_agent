package com.example.be.domain.issues.service;

import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IssueCrossSourceWriter {

    private static final BigDecimal REPRESENTATIVE_CONFIDENCE = new BigDecimal("0.900");

    private final NewsIssueRepository issueRepository;
    private final IssueArticleRepository issueArticleRepository;

    @Transactional
    public void applyRepresentative(Long issueId,
                                    IssueCrossSource crossSource,
                                    List<RuleStance> ruleStances,
                                    boolean llmConfirmed) {
        NewsIssue issue = issueRepository.findByIdForUpdate(issueId)
                .orElseThrow(() -> new IllegalStateException("교차 비교를 저장할 이슈가 없습니다. id=" + issueId));
        List<IssueArticle> memberships = issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(issueId);
        Set<Long> articleIds = memberships.stream()
                .map(membership -> membership.getArticle().getId())
                .collect(java.util.stream.Collectors.toSet());
        IssueCrossSource currentCrossSource = currentReferences(crossSource, articleIds);

        Map<Long, RuleStance> stanceByArticle = new HashMap<>();
        List<RuleStance> candidates = ruleStances == null ? List.of() : ruleStances;
        for (RuleStance candidate : candidates) {
            // 긴 Agent 왕복 사이 다른 이슈로 이동한 멤버는 stale snapshot이므로 건너뛴다.
            if (!articleIds.contains(candidate.articleId())) {
                continue;
            }
            if (stanceByArticle.putIfAbsent(candidate.articleId(), candidate) != null) {
                throw new IllegalArgumentException("memberStances가 이슈 기사와 일치하지 않습니다.");
            }
        }

        issue.applyCrossSource(currentCrossSource);
        memberships.forEach(membership -> {
            if (membership.getRole() == IssueArticleRole.REPRESENTATIVE) {
                membership.applyStance(
                        IssueStance.SUPPORTS,
                        llmConfirmed ? IssueStanceSource.LLM : IssueStanceSource.RULE,
                        REPRESENTATIVE_CONFIDENCE);
                return;
            }
            RuleStance candidate = stanceByArticle.get(membership.getArticle().getId());
            if (candidate == null || membership.getStanceSource() == IssueStanceSource.LLM) {
                return;
            }
            membership.applyStance(
                    candidate.stance(),
                    IssueStanceSource.RULE,
                    candidate.confidence());
        });
    }

    @Transactional
    public void confirmPromotion(Long issueId,
                                 Long articleId,
                                 IssueStance stance,
                                 BigDecimal confidence,
                                 boolean llmConfirmed) {
        issueRepository.findByIdForUpdate(issueId)
                .orElseThrow(() -> new IllegalStateException("승격 결과를 저장할 이슈가 없습니다. id=" + issueId));
        IssueArticle membership = issueArticleRepository.findByIssueIdAndArticleId(issueId, articleId)
                .orElseThrow(() -> new IllegalStateException(
                        "승격 결과를 저장할 이슈 기사가 없습니다. issueId=" + issueId
                                + " articleId=" + articleId));
        membership.applyStance(
                stance,
                llmConfirmed ? IssueStanceSource.LLM : IssueStanceSource.RULE,
                confidence);
    }

    private IssueCrossSource currentReferences(IssueCrossSource crossSource, Set<Long> articleIds) {
        if (crossSource == null) {
            throw new IllegalArgumentException("crossSource는 필수입니다.");
        }
        List<IssueCrossSource.SoleSource> soleSource = crossSource.soleSource().stream()
                .filter(value -> articleIds.contains(value.articleId()))
                .toList();
        List<IssueCrossSource.Conflict> conflicts = crossSource.conflicts().stream()
                .map(value -> new IssueCrossSource.Conflict(
                        value.articleIds().stream().filter(articleIds::contains).toList(),
                        value.text()))
                .filter(value -> value.articleIds().size() >= 2)
                .toList();
        return new IssueCrossSource(
                crossSource.consensus(),
                soleSource,
                conflicts,
                crossSource.missingStakeholders());
    }

    public record RuleStance(Long articleId, IssueStance stance, BigDecimal confidence) {

        public RuleStance {
            if (articleId == null || stance == null || confidence == null
                    || confidence.signum() < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("RULE stance 후보가 올바르지 않습니다.");
            }
        }
    }
}
