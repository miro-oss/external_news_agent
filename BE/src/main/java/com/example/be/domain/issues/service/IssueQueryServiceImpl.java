package com.example.be.domain.issues.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.repository.FindingToneSnapshot;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.dto.res.IssueResDTO;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
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
import java.util.List;

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
        List<FindingToneSnapshot> latestFindings = OracleInClause.batches(memberships.stream()
                        .map(value -> value.getArticle().getId()).distinct().toList()).stream()
                .flatMap(ids -> findingRepository.findLatestToneByArticleIds(ids).stream())
                .toList();
        IssueArticle representative = memberships.stream()
                .filter(value -> value.getRole() == IssueArticleRole.REPRESENTATIVE)
                .findFirst()
                .orElse(null);
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
                .crossSource(issue.getCrossSource())
                .toneDistribution(toneCalculator.calculate(memberships, latestFindings))
                .representativeArticleId(representative == null ? null : representative.getArticle().getId())
                .articles(memberships.stream().map(this::toArticle).toList())
                .build();
    }

    private IssueResDTO.Article toArticle(IssueArticle membership) {
        Article article = membership.getArticle();
        return IssueResDTO.Article.builder()
                .id(article.getId())
                .title(article.getTitle())
                .publisher(publisher(article))
                .canonicalUrl(article.getCanonicalUrl())
                .publishedAt(article.getPublishedAt())
                .contentGroupId(article.getContentGroup() == null ? null : article.getContentGroup().getId())
                .role(membership.getRole().name())
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
