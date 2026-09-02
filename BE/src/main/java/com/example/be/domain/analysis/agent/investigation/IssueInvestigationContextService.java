package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentExploreRequest;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.analysis.service.SentenceSplitter;
import com.example.be.domain.collection.cluster.BreakingNewsDetector;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.sources.entity.SearchProvider;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class IssueInvestigationContextService {

    private static final long SOLO_BREAKING_HOURS = 24;

    private final IssueArticleRepository issueArticleRepository;
    private final CollectionRunArticleRepository runArticleRepository;
    private final FindingRepository findingRepository;
    private final SourceRepository sourceRepository;
    private final BreakingNewsDetector breakingNewsDetector;
    private final AgentProperties properties;

    public List<InvestigationContext> candidates(Long runId) {
        Map<Long, IssueArticle> representatives = new LinkedHashMap<>();
        issueArticleRepository.findRepresentativesForRun(runId).forEach(membership ->
                representatives.putIfAbsent(membership.getIssue().getId(), membership));
        List<IssueArticle> ordered = representatives.values().stream()
                .sorted(Comparator.comparing(
                                (IssueArticle value) -> value.getIssue().getImportanceScore(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(value -> value.getIssue().getId()))
                .toList();
        Set<Long> topImportance = ordered.stream()
                .limit(properties.getInvestigation().getCandidateLimit())
                .map(value -> value.getIssue().getId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> observedArticleIds = new LinkedHashSet<>(
                runArticleRepository.findArticleIdsByRunId(runId));
        Map<Long, SourceAccess> sourceAccessByTopic = new HashMap<>();
        List<InvestigationContext> contexts = new ArrayList<>();
        for (IssueArticle representative : ordered) {
            boolean topImportanceCandidate = topImportance.contains(
                    representative.getIssue().getId());
            if (!requiresContext(representative, topImportanceCandidate)) {
                continue;
            }
            List<IssueArticle> memberships = issueArticleRepository
                    .findByIssueIdOrderByJoinedAtAsc(representative.getIssue().getId());
            Long topicId = representative.getIssue().getTopic().getId();
            SourceAccess sourceAccess = sourceAccessByTopic.computeIfAbsent(
                    topicId, this::sourceAccess);
            InvestigationContext context = context(
                    runId, representative, memberships, observedArticleIds, sourceAccess);
            String trigger = triggerReason(context, topImportance.contains(context.issueId()));
            if (trigger != null) {
                contexts.add(withTrigger(context, trigger));
            }
        }
        return List.copyOf(contexts);
    }

    public InvestigationContext current(Long runId, Long issueId) {
        List<IssueArticle> memberships = issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(issueId);
        IssueArticle representative = memberships.stream()
                .filter(value -> value.getRole() == IssueArticleRole.REPRESENTATIVE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("조사할 이슈 대표 기사가 없습니다. issueId=" + issueId));
        Set<Long> observedArticleIds = new LinkedHashSet<>(
                runArticleRepository.findArticleIdsByRunId(runId));
        return context(runId, representative, memberships, observedArticleIds,
                sourceAccess(representative.getIssue().getTopic().getId()));
    }

    private InvestigationContext context(Long runId,
                                         IssueArticle representative,
                                         List<IssueArticle> memberships,
                                         Set<Long> observedArticleIds,
                                         SourceAccess sourceAccess) {
        NewsIssue issue = representative.getIssue();
        List<Long> metadataOnlyIds = memberships.stream()
                .map(IssueArticle::getArticle)
                .filter(article -> observedArticleIds.contains(article.getId()))
                .filter(article -> article.getFetchStatus() == FetchStatus.METADATA_ONLY
                        || article.getFetchStatus() == FetchStatus.FETCH_FAILED)
                .map(article -> article.getId())
                .toList();
        List<Long> articleIds = memberships.stream()
                .map(value -> value.getArticle().getId())
                .toList();
        int availableSentenceCount = memberships.stream()
                .map(IssueArticle::getArticle)
                .filter(article -> article.getFetchStatus() == FetchStatus.FULLTEXT)
                .mapToInt(article -> SentenceSplitter.split(article.getBody(), article.getLanguage()).size())
                .sum();
        int evidenceCount = supportedEvidenceCount(runId, articleIds);
        IssueCrossSource crossSource = issue.getCrossSource() == null
                ? IssueCrossSource.empty() : issue.getCrossSource();
        boolean breakingSolo = breakingSolo(issue, representative);
        return new InvestigationContext(
                issue.getId(), issue.getTopic().getId(), issue.getTitle(), issue.getSummary(),
                issue.getStatus().name(), issue.getImportanceScore(), issue.getSensitivityScore(),
                issue.getEntities(), crossSource.missingStakeholders(), evidenceCount,
                availableSentenceCount, articleIds, metadataOnlyIds,
                sourceAccess.allowedSources(), sourceAccess.sourceIdsByKey(), breakingSolo, "");
    }

    private boolean requiresContext(IssueArticle representative, boolean topImportance) {
        NewsIssue issue = representative.getIssue();
        IssueCrossSource crossSource = issue.getCrossSource() == null
                ? IssueCrossSource.empty() : issue.getCrossSource();
        return topImportance
                || issue.getStatus() == IssueStatus.DISPUTED
                || breakingSolo(issue, representative)
                || !crossSource.missingStakeholders().isEmpty();
    }

    private boolean breakingSolo(NewsIssue issue, IssueArticle representative) {
        return issue.getArticleCount() == 1
                && breakingNewsDetector.hasExplicitMarker(representative.getArticle().getTitle())
                && Duration.between(issue.getFirstSeenAt(), OffsetDateTime.now(ApiTimeZone.ZONE))
                .toHours() >= SOLO_BREAKING_HOURS;
    }

    private SourceAccess sourceAccess(Long topicId) {
        Map<String, Long> sourceIds = new LinkedHashMap<>();
        List<AgentExploreRequest.AllowedSource> allowedSources = new ArrayList<>();
        for (Source source : sourceRepository.findActiveByTopicId(topicId)) {
            String key = sourceKey(source);
            if (key == null || sourceIds.putIfAbsent(key, source.getId()) != null) {
                continue;
            }
            allowedSources.add(new AgentExploreRequest.AllowedSource(
                    key, source.getName(), source.getSourceKind()));
        }
        return new SourceAccess(List.copyOf(allowedSources), Map.copyOf(sourceIds));
    }

    private String triggerReason(InvestigationContext context, boolean topImportance) {
        List<String> reasons = new ArrayList<>();
        if (topImportance
                && context.evidenceSentenceCount()
                < properties.getInvestigation().getEvidenceThreshold()) {
            reasons.add("상위 중요도 이슈의 근거 문장이 부족함");
        }
        if (IssueStatus.DISPUTED.name().equals(context.status())) {
            reasons.add("상충 보도 상태");
        }
        if (context.breakingSoloAfter24Hours()) {
            reasons.add("24시간 단독 속보");
        }
        if (!context.missingStakeholders().isEmpty()) {
            reasons.add("빠진 이해관계자: " + String.join(", ", context.missingStakeholders()));
        }
        return reasons.isEmpty() ? null : String.join(" · ", reasons);
    }

    private InvestigationContext withTrigger(InvestigationContext value, String trigger) {
        return new InvestigationContext(
                value.issueId(), value.topicId(), value.title(), value.summary(), value.status(),
                value.importanceScore(), value.sensitivityScore(), value.entities(),
                value.missingStakeholders(), value.evidenceSentenceCount(),
                value.availableSentenceCount(), value.articleIds(), value.metadataOnlyArticleIds(),
                value.allowedSources(), value.sourceIdsByKey(),
                value.breakingSoloAfter24Hours(), trigger);
    }

    private int supportedEvidenceCount(Long runId, List<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return 0;
        }
        return findingRepository.findByRunIdAndArticleIdIn(runId, articleIds).stream()
                .mapToInt(finding -> FindingEvidencePolicy.supportedKeyPoints(finding).stream()
                        .flatMap(point -> point.evidence().stream())
                        .distinct()
                        .toList()
                        .size())
                .sum();
    }

    private String sourceKey(Source source) {
        if (source.isSearchKind()) {
            return SearchProvider.fromKey(source.getUrlTemplate()) == SearchProvider.NAVER
                    ? SearchProvider.NAVER.name() : null;
        }
        return Source.KIND_FEED.equals(source.getSourceKind()) && StringUtils.hasText(source.getName())
                ? "FEED:" + source.getId() : null;
    }

    private record SourceAccess(
            List<AgentExploreRequest.AllowedSource> allowedSources,
            Map<String, Long> sourceIdsByKey
    ) {
    }
}
