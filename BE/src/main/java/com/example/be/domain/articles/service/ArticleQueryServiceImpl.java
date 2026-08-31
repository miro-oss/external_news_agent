package com.example.be.domain.articles.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.AudienceRelevance;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingCategory;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.articles.dto.res.ArticleResDTO;
import com.example.be.domain.articles.exception.ArticleException;
import com.example.be.domain.articles.exception.code.ArticleErrorCode;
import com.example.be.domain.articles.repository.FindingSpecification;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArticleQueryServiceImpl implements ArticleQueryService {

    private final FindingRepository findingRepository;
    private final ArticleRepository articleRepository;
    private final IssueArticleRepository issueArticleRepository;

    @Override
    public PageResponse<ArticleResDTO.Summary> getArticles(Long runId,
                                                            Long topicId,
                                                            Long sourceId,
                                                            String changeType,
                                                            String relevance,
                                                            String riskLevel,
                                                            String category,
                                                            String language,
                                                            String audience,
                                                            String minAudienceRelevance,
                                                            OffsetDateTime from,
                                                            OffsetDateTime to,
                                                            String sort,
                                                            int page,
                                                            int size) {
        validatePage(page, size);
        validatePeriod(from, to);
        String normalizedSort = normalizeSort(sort);
        String normalizedCategory = normalizeCategory(category);
        Audience parsedAudience = parseAudience(audience);
        AudienceRelevance parsedAudienceRelevance = parseAudienceRelevance(
                parsedAudience, minAudienceRelevance);

        Page<Finding> findings = findingRepository.findAll(
                FindingSpecification.latestWithFilters(
                        runId,
                        topicId,
                        sourceId,
                        parseChangeType(changeType),
                        parseRelevance(relevance),
                        parseRiskLevel(riskLevel),
                        normalizedCategory,
                        normalize(language),
                        parsedAudience,
                        parsedAudienceRelevance,
                        from,
                        to,
                        normalizedSort),
                PageRequest.of(page, size));

        return PageResponse.of(findings.getContent().stream().map(this::toSummary).toList(),
                page, size, findings.getTotalElements());
    }

    @Override
    public ArticleResDTO.Detail getArticle(Long articleId, Long runId) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ArticleException(ArticleErrorCode.ARTICLE_NOT_FOUND));
        IssueArticle membership = primaryMembership(article);
        Article analyzedArticle = representativeArticle(membership, article);
        Finding finding = findFinding(analyzedArticle.getId(), runId);
        if (finding == null && !analyzedArticle.getId().equals(articleId)) {
            // 이관 전 레거시 finding이 멤버에만 있으면 상세 분석을 갑자기 비우지 않는다.
            analyzedArticle = article;
            finding = findFinding(articleId, runId);
        }

        boolean hasAnalyzedBody = finding != null && StringUtils.hasText(analyzedArticle.getBody());
        List<FindingSection> sections = hasAnalyzedBody ? finding.getSections() : List.of();
        String bodyText = hasAnalyzedBody
                ? sections.stream().map(FindingSection::text).collect(Collectors.joining(" "))
                : article.getBody();

        return ArticleResDTO.Detail.builder()
                .id(article.getId())
                .title(article.getTitle())
                .publisher(publisher(article))
                .canonicalUrl(article.getCanonicalUrl())
                .language(article.getLanguage())
                .publishedAt(article.getPublishedAt())
                .fetchedAt(fetchedAt(article))
                .fetchStatus(article.getFetchStatus().toApiValue())
                .topicId(article.getTopic().getId())
                .topicName(article.getTopic().getName())
                .sourceId(article.getSource().getId())
                .sourceName(article.getSource().getName())
                .bodyText(bodyText)
                .sentences(sections.stream().map(section -> ArticleResDTO.Sentence.builder()
                        .index(section.index())
                        .text(section.text())
                        .build()).toList())
                .analysis(toAnalysis(finding))
                .issueId(membership == null ? null : membership.getIssue().getId())
                .relatedArticles(relatedArticles(membership, articleId))
                .build();
    }

    private Finding findFinding(Long articleId, Long runId) {
        return runId == null
                ? findingRepository.findFirstByArticleIdOrderByIdDesc(articleId).orElse(null)
                : findingRepository.findByRunIdAndArticleId(runId, articleId).orElse(null);
    }

    private IssueArticle primaryMembership(Article article) {
        List<IssueArticle> memberships = issueArticleRepository.findByArticleIdOrderByIssueIdAsc(article.getId());
        return memberships.stream()
                .filter(value -> value.getIssue().getTopic().getId().equals(article.getTopic().getId()))
                .findFirst()
                .orElse(memberships.isEmpty() ? null : memberships.getFirst());
    }

    private Article representativeArticle(IssueArticle membership, Article fallback) {
        if (membership == null || membership.getRole() == IssueArticleRole.REPRESENTATIVE) {
            return fallback;
        }
        return issueArticleRepository.findFirstByIssueIdAndRole(
                        membership.getIssue().getId(), IssueArticleRole.REPRESENTATIVE)
                .map(IssueArticle::getArticle)
                .orElse(fallback);
    }

    private List<ArticleResDTO.RelatedArticle> relatedArticles(IssueArticle membership, Long articleId) {
        if (membership == null) {
            return List.of();
        }
        return issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(membership.getIssue().getId()).stream()
                .map(IssueArticle::getArticle)
                .filter(article -> !article.getId().equals(articleId))
                .map(article -> ArticleResDTO.RelatedArticle.builder()
                        .id(article.getId())
                        .title(article.getTitle())
                        .publisher(publisher(article))
                        .build())
                .toList();
    }

    private ArticleResDTO.Summary toSummary(Finding finding) {
        Article article = finding.getArticle();
        return ArticleResDTO.Summary.builder()
                .id(article.getId())
                .title(article.getTitle())
                .publisher(publisher(article))
                .canonicalUrl(article.getCanonicalUrl())
                .urlHash(article.getUrlHash())
                .language(article.getLanguage())
                .publishedAt(article.getPublishedAt())
                .fetchedAt(fetchedAt(article))
                .fetchStatus(article.getFetchStatus().toApiValue())
                .topicId(article.getTopic().getId())
                .topicName(article.getTopic().getName())
                .sourceId(article.getSource().getId())
                .sourceName(article.getSource().getName())
                .changeType(finding.getChangeType().name())
                .summary(finding.getSummary())
                .category(finding.getCategory())
                .relevance(finding.getRelevance().toApiValue())
                .riskLevel(finding.getRiskLevel().toApiValue())
                .sentiment(finding.getSentiment().toApiValue())
                .perspectiveTags(toPerspectiveTags(finding))
                .build();
    }

    private ArticleResDTO.Analysis toAnalysis(Finding finding) {
        if (finding == null) {
            return null;
        }
        return ArticleResDTO.Analysis.builder()
                .changeType(finding.getChangeType().name())
                .summary(finding.getSummary())
                .keyPoints(finding.getEffectiveKeyPoints().stream().map(point -> ArticleResDTO.KeyPoint.builder()
                        .text(point.text())
                        .evidence(point.evidence())
                        .groundedness(point.groundedness())
                        .build()).toList())
                .intent(finding.getIntent())
                .sentiment(finding.getSentiment().toApiValue())
                .riskLevel(finding.getRiskLevel().toApiValue())
                .relevance(finding.getRelevance().toApiValue())
                .category(finding.getCategory())
                .perspectiveTags(toPerspectiveTags(finding))
                .analyzedAt(toOffset(finding.getAnalyzedAt()))
                .runId(finding.getRun().getId())
                .build();
    }

    private List<ArticleResDTO.PerspectiveTag> toPerspectiveTags(Finding finding) {
        return finding.getPerspectiveTags() == null ? List.of() : finding.getPerspectiveTags().stream()
                .map(tag -> ArticleResDTO.PerspectiveTag.builder()
                        .audience(tag.audience().name())
                        .relevance(tag.relevance().toApiValue())
                        .hook(tag.hook())
                        .evidenceSentenceIds(tag.evidenceSentenceIds())
                        .build())
                .toList();
    }

    private String publisher(Article article) {
        return StringUtils.hasText(article.getSourceName()) ? article.getSourceName() : article.getSource().getName();
    }

    private OffsetDateTime fetchedAt(Article article) {
        LocalDateTime value = article.getUpdatedAt() == null ? article.getCollectedAt() : article.getUpdatedAt();
        return toOffset(value);
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private ChangeType parseChangeType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            ChangeType parsed = ChangeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (parsed == ChangeType.UNCHANGED) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw badRequest("changeType은 NEW 또는 UPDATED여야 합니다.");
        }
    }

    private Relevance parseRelevance(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Relevance.fromApiValue(value.trim());
        } catch (IllegalArgumentException exception) {
            throw badRequest("relevance는 important, watch, reference 중 하나여야 합니다.");
        }
    }

    private RiskLevel parseRiskLevel(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return RiskLevel.fromApiValue(value.trim());
        } catch (IllegalArgumentException exception) {
            throw badRequest("riskLevel은 low, medium, high 중 하나여야 합니다.");
        }
    }

    private String normalizeCategory(String value) {
        String normalized = normalize(value);
        if (normalized != null && !FindingCategory.ALLOWED_VALUES.contains(normalized)) {
            throw badRequest("category는 제품/공정, 기업, 정책, 공급망 중 하나여야 합니다.");
        }
        return normalized;
    }

    private String normalizeSort(String value) {
        String normalized = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : FindingSpecification.SORT_PUBLISHED_DESC;
        if (!Set.of(FindingSpecification.SORT_PUBLISHED_DESC,
                FindingSpecification.SORT_PUBLISHED_ASC,
                FindingSpecification.SORT_RISK_DESC).contains(normalized)) {
            throw badRequest("지원하지 않는 정렬 조건입니다.");
        }
        return normalized;
    }

    private Audience parseAudience(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Audience.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new AudienceException();
        }
    }

    private AudienceRelevance parseAudienceRelevance(Audience audience, String value) {
        if (audience == null) {
            if (StringUtils.hasText(value)) {
                throw new AudienceException();
            }
            return null;
        }
        if (!StringUtils.hasText(value)) {
            return AudienceRelevance.MEDIUM;
        }
        try {
            return AudienceRelevance.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new AudienceException();
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void validatePage(int page, int size) {
        if (page < PageResponse.DEFAULT_PAGE) {
            throw badRequest("page는 0 이상이어야 합니다.");
        }
        if (size < PageResponse.MIN_SIZE || size > PageResponse.MAX_SIZE) {
            throw badRequest("size는 1 이상 100 이하여야 합니다.");
        }
    }

    private void validatePeriod(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw badRequest("from은 to보다 이전이어야 합니다.");
        }
    }

    private GeneralException badRequest(String message) {
        return new GeneralException(GeneralErrorCode.BAD_REQUEST, message);
    }
}
