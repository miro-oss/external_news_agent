package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 현재 기사 stance와 교차 출처 관측으로 이슈 상태를 매번 처음부터 계산한다. */
@Component
@RequiredArgsConstructor
public class IssueStatusCalculator {

    private static final BigDecimal CORROBORATION_CONFIDENCE = new BigDecimal("0.800");

    private final OfficialCorrectionPolicy officialCorrectionPolicy;

    public Projection calculate(NewsIssue issue, List<IssueArticle> memberships) {
        List<IssueArticle> current = memberships == null ? List.of() : List.copyOf(memberships);
        List<IssueArticle> llmRetractions = current.stream()
                .filter(value -> value.getStance() == IssueStance.RETRACTS)
                .filter(value -> value.getStanceSource() == IssueStanceSource.LLM)
                .toList();
        if (hasIndependentConfirmation(llmRetractions)) {
            return new Projection(IssueStatus.RETRACTED, "서로 다른 매체·독립 본문 2건의 LLM 철회 확인");
        }
        if (current.stream().anyMatch(value -> officialCorrectionPolicy.matches(issue, value))) {
            return new Projection(IssueStatus.RETRACTED, "이슈 엔티티에 매핑된 공식 발표 도메인의 정정");
        }

        if (current.stream().anyMatch(value -> value.getStance() == IssueStance.DISPUTES
                && value.getStanceSource() == IssueStanceSource.LLM)) {
            return new Projection(IssueStatus.DISPUTED, "LLM 확인 반박 기사 관측");
        }
        if (hasIndependentConflict(issue.getCrossSource(), current)) {
            return new Projection(IssueStatus.DISPUTED, "서로 다른 매체·독립 본문 간 교차 출처 충돌");
        }

        List<IssueArticle> supports = current.stream()
                .filter(value -> value.getStance() == IssueStance.SUPPORTS)
                .filter(value -> value.getStanceConfidence() != null)
                .filter(value -> value.getStanceConfidence().compareTo(CORROBORATION_CONFIDENCE) >= 0)
                .toList();
        if (hasIndependentConfirmation(supports)) {
            return new Projection(IssueStatus.CORROBORATED, "서로 다른 매체·독립 본문 2건의 고신뢰 지지");
        }
        return new Projection(IssueStatus.EMERGING, "고신뢰 독립 확인 또는 확인된 반박 미충족");
    }

    private boolean hasIndependentConflict(IssueCrossSource crossSource,
                                           List<IssueArticle> memberships) {
        if (crossSource == null || crossSource.conflicts().isEmpty()) {
            return false;
        }
        Map<Long, IssueArticle> byArticleId = memberships.stream()
                .collect(Collectors.toMap(
                        value -> value.getArticle().getId(),
                        Function.identity(),
                        (left, right) -> left,
                        HashMap::new));
        return crossSource.conflicts().stream().anyMatch(conflict -> {
            List<IssueArticle> participants = conflict.articleIds().stream()
                    .map(byArticleId::get)
                    .filter(value -> value != null)
                    .toList();
            return hasIndependentConfirmation(participants);
        });
    }

    private boolean hasIndependentConfirmation(List<IssueArticle> memberships) {
        Set<String> publishers = memberships.stream()
                .map(value -> publisher(value.getArticle()))
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> contents = memberships.stream()
                .map(value -> contentKey(value.getArticle()))
                .collect(Collectors.toSet());
        return publishers.size() >= 2 && contents.size() >= 2;
    }

    private String publisher(Article article) {
        if (StringUtils.hasText(article.getSourceName())) {
            return article.getSourceName();
        }
        return article.getSource() == null ? null : article.getSource().getName();
    }

    private String contentKey(Article article) {
        return article.getContentGroup() == null
                ? "article:" + article.getId()
                : "group:" + article.getContentGroup().getId();
    }

    public record Projection(IssueStatus status, String reason) {

        public Projection {
            if (status == null || !StringUtils.hasText(reason)) {
                throw new IllegalArgumentException("이슈 상태 projection이 올바르지 않습니다.");
            }
        }
    }
}
