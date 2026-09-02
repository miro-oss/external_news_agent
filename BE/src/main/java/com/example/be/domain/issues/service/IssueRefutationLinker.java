package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueRelation;
import com.example.be.domain.issues.entity.IssueRelationType;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueRelationRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** 검증 실패·정정이 별도 이슈가 된 경우 원 이슈로 REFUTES 관계를 잇는다. */
@Service
@RequiredArgsConstructor
public class IssueRefutationLinker {

    private static final int LOOKBACK_DAYS = 7;

    private final IssueArticleRepository issueArticleRepository;
    private final IssueRelationRepository issueRelationRepository;
    private final IssueStanceClassifier stanceClassifier;

    public Optional<Long> linkNewIssue(NewsIssue newIssue, Article representative) {
        if (!stanceClassifier.hasExplicitCorrection(representative)) {
            return Optional.empty();
        }
        OffsetDateTime since = newIssue.getFirstSeenAt().minusDays(LOOKBACK_DAYS);
        Optional<IssueArticle> refuted = issueArticleRepository
                .findRecentRepresentativesByTopicIdExcludingIssueId(
                        newIssue.getTopic().getId(), newIssue.getId(), since)
                .stream()
                .filter(candidate -> sharesEnoughEntities(newIssue, candidate.getIssue()))
                .filter(candidate -> stanceClassifier.classify(
                        candidate.getArticle(), representative).stance() == IssueStance.RETRACTS)
                .max(Comparator.comparing(value -> value.getIssue().getLastSeenAt()));
        if (refuted.isEmpty()) {
            return Optional.empty();
        }
        NewsIssue original = refuted.get().getIssue();
        if (!issueRelationRepository.existsByFromIssueIdAndToIssueIdAndRelationType(
                newIssue.getId(), original.getId(), IssueRelationType.REFUTES)) {
            issueRelationRepository.save(IssueRelation.builder()
                    .fromIssue(newIssue)
                    .toIssue(original)
                    .relationType(IssueRelationType.REFUTES)
                    .createdAt(LocalDateTime.now(ApiTimeZone.ZONE))
                    .build());
        }
        return Optional.of(original.getId());
    }

    private boolean sharesEnoughEntities(NewsIssue left, NewsIssue right) {
        Set<String> entities = new HashSet<>(left.getEntities());
        entities.retainAll(right.getEntities());
        return entities.size() >= 2;
    }
}
