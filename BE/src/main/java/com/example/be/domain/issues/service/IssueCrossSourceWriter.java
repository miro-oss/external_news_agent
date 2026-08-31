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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IssueCrossSourceWriter {

    private static final BigDecimal REPRESENTATIVE_CONFIDENCE = new BigDecimal("0.900");
    private static final BigDecimal DEFAULT_MEMBER_CONFIDENCE = new BigDecimal("0.500");

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
        validateReferences(crossSource, articleIds);

        Map<Long, RuleStance> stanceByArticle = new HashMap<>();
        List<RuleStance> candidates = ruleStances == null ? List.of() : ruleStances;
        for (RuleStance candidate : candidates) {
            if (!articleIds.contains(candidate.articleId())
                    || stanceByArticle.putIfAbsent(candidate.articleId(), candidate) != null) {
                throw new IllegalArgumentException("memberStances가 이슈 기사와 일치하지 않습니다.");
            }
        }

        issue.applyCrossSource(crossSource);
        memberships.forEach(membership -> {
            if (membership.getRole() == IssueArticleRole.REPRESENTATIVE) {
                membership.applyStance(
                        IssueStance.SUPPORTS,
                        llmConfirmed ? IssueStanceSource.LLM : IssueStanceSource.RULE,
                        REPRESENTATIVE_CONFIDENCE);
                return;
            }
            RuleStance candidate = stanceByArticle.get(membership.getArticle().getId());
            membership.applyStance(
                    candidate == null ? IssueStance.SUPPORTS : candidate.stance(),
                    IssueStanceSource.RULE,
                    candidate == null ? DEFAULT_MEMBER_CONFIDENCE : candidate.confidence());
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

    private void validateReferences(IssueCrossSource crossSource, Set<Long> articleIds) {
        if (crossSource == null) {
            throw new IllegalArgumentException("crossSource는 필수입니다.");
        }
        Set<Long> references = new HashSet<>();
        crossSource.soleSource().forEach(value -> references.add(value.articleId()));
        crossSource.conflicts().forEach(value -> references.addAll(value.articleIds()));
        if (!articleIds.containsAll(references)) {
            throw new IllegalArgumentException("crossSource가 이슈 밖의 기사를 참조합니다.");
        }
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
