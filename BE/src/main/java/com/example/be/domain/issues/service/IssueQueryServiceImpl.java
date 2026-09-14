package com.example.be.domain.issues.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.repository.FindingToneSnapshot;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.dto.res.IssueResDTO;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.exception.IssueException;
import com.example.be.domain.issues.exception.code.IssueErrorCode;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueQueryServiceImpl implements IssueQueryService {

    private final NewsIssueRepository issueRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final FindingRepository findingRepository;
    private final IssueToneCalculator toneCalculator;

    @Override
    public IssueResDTO.Detail getIssue(Long issueId) {
        NewsIssue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_NOT_FOUND));
        List<IssueArticle> memberships = issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(issueId);
        List<IssueArticle> visibleMemberships = memberships.stream()
                .filter(value -> value.getArticle().hasFullText())
                .toList();
        Set<Long> visibleArticleIds = visibleMemberships.stream()
                .map(value -> value.getArticle().getId()).collect(Collectors.toSet());
        // Collection counts and tone observations keep their current-membership contract.
        // The full-text policy only changes the article links exposed by this response.
        List<FindingToneSnapshot> latestFindings = OracleInClause.batches(memberships.stream()
                        .map(value -> value.getArticle().getId()).distinct().toList()).stream()
                .flatMap(ids -> findingRepository.findLatestToneByArticleIds(ids).stream())
                .toList();
        IssueArticle representative = visibleMemberships.stream()
                .filter(value -> value.getRole() == IssueArticleRole.REPRESENTATIVE)
                .findFirst()
                .orElseGet(() -> visibleMemberships.stream()
                        .min(Comparator.comparing((IssueArticle value) -> value.getArticle().getPublishedAt(),
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(value -> value.getArticle().getId()))
                        .orElse(null));
        Long representativeId = representative == null ? null : representative.getArticle().getId();
        String summary = issue.getSummary();
        if (!StringUtils.hasText(summary) && representative != null) {
            summary = findingRepository.findLatestSummaryByArticleId(representative.getArticle().getId())
                    .orElse(null);
        }

        return IssueResDTO.Detail.builder()
                .id(issue.getId())
                .title(issue.getTitle())
                .summary(summary)
                .status(issue.getStatus().name())
                .importanceScore(issue.getImportanceScore())
                .sensitivityScore(issue.getSensitivityScore())
                .firstSeenAt(issue.getFirstSeenAt())
                .lastSeenAt(issue.getLastSeenAt())
                .articleCount(issue.getArticleCount())
                .publisherCount(issue.getPublisherCount())
                .independentContentCount(issue.getIndependentContentCount())
                .topicId(issue.getTopic().getId())
                .topicName(issue.getTopic().getName())
                .entities(issue.getEntities())
                .crossSource(visibleCrossSource(issue.getCrossSource(), visibleArticleIds))
                .toneDistribution(toneCalculator.calculate(memberships, latestFindings))
                .representativeArticleId(representativeId)
                .articles(visibleMemberships.stream().map(value -> toArticle(value, representativeId)).toList())
                .build();
    }

    private IssueCrossSource visibleCrossSource(IssueCrossSource source, Set<Long> visibleArticleIds) {
        if (source == null) {
            return IssueCrossSource.empty();
        }
        return new IssueCrossSource(source.consensus(),
                source.soleSource().stream()
                        .filter(value -> visibleArticleIds.contains(value.articleId())).toList(),
                source.conflicts().stream()
                        // Dropping only some participants would change the stored conflict's meaning.
                        .filter(value -> !value.articleIds().isEmpty()
                                && visibleArticleIds.containsAll(value.articleIds())).toList(),
                source.missingStakeholders());
    }

    private IssueResDTO.Article toArticle(IssueArticle membership, Long representativeId) {
        Article article = membership.getArticle();
        return IssueResDTO.Article.builder()
                .id(article.getId())
                .title(article.getTitle())
                .publisher(publisher(article))
                .canonicalUrl(article.getCanonicalUrl())
                .publishedAt(article.getPublishedAt())
                .contentGroupId(article.getContentGroup() == null ? null : article.getContentGroup().getId())
                .role(Objects.equals(article.getId(), representativeId)
                        ? IssueArticleRole.REPRESENTATIVE.name() : membership.getRole().name())
                .stance(membership.getStance().name())
                .stanceSource(membership.getStanceSource().name())
                .stanceConfidence(membership.getStanceConfidence())
                .joinedAt(toOffset(membership.getJoinedAt()))
                .build();
    }

    private String publisher(Article article) {
        return StringUtils.hasText(article.getSourceName())
                ? article.getSourceName()
                : article.getSource().getName();
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }
}
