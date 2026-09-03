package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.cluster.IssueClusteringService;
import com.example.be.domain.collection.service.command.ArticleContentEnricher;
import com.example.be.domain.collection.service.command.CollectionExecutor;
import com.example.be.domain.collection.service.command.CollectionOutcome;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 승인된 조사 행동만 수행하는 외부 I/O 경계다. */
@Component
@RequiredArgsConstructor
public class IssueInvestigationActionExecutor {

    private final SourceRepository sourceRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final CollectionExecutor collectionExecutor;
    private final CollectionResultWriter resultWriter;
    private final ArticleContentEnricher contentEnricher;
    private final IssueClusteringService issueClusteringService;
    private final ArticleAnalysisPipeline analysisPipeline;
    private final IssueInvestigationContextService contextService;
    private final AgentProperties properties;

    public InvestigationActionResult execute(Long runId,
                                             InvestigationContext before,
                                             AgentExploreResponse.Proposal proposal) {
        return switch (proposal.action()) {
            case "SEARCH_MORE" -> search(runId, before, proposal);
            case "READ_FULLTEXT" -> readFullText(runId, before, proposal.articleId());
            case "COMPARE_HISTORY" -> compareHistory(before, proposal);
            case "CONCLUDE" -> InvestigationActionResult.conclude(proposal.reason());
            default -> throw new IllegalArgumentException("실행할 수 없는 조사 행동입니다: " + proposal.action());
        };
    }

    private InvestigationActionResult search(Long runId,
                                             InvestigationContext before,
                                             AgentExploreResponse.Proposal proposal) {
        Long sourceId = before.sourceIdsByKey().get(proposal.sourceKey());
        Source source = sourceRepository.findActiveByTopicId(before.topicId()).stream()
                .filter(value -> value.getId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "승인 뒤 비활성화되거나 주제에서 해제된 소스입니다. sourceId=" + sourceId));
        CollectionOutcome outcome = collectionExecutor.collectInvestigation(
                proposal.query(), properties.getInvestigation().getSearchBatchSize(), source);
        CollectionResultWriter.InvestigationWriteResult write = resultWriter.writeInvestigation(
                runId, before.topicId(), sourceId, outcome);
        if (!outcome.robots().allowed()) {
            throw new IllegalStateException("robots.txt가 추가 수집을 허용하지 않았습니다.");
        }
        if (!outcome.fetch().success()) {
            throw new IllegalStateException("추가 수집 실패: " + outcome.fetch().failureMessage());
        }
        Set<Long> refreshedArticleIds = contentEnricher.enrich(runId);
        issueClusteringService.cluster(runId);
        InvestigationContext after = analyzeChanges(
                runId, before, refreshedArticleIds, contextService.current(runId, before.issueId()));
        return new InvestigationActionResult(
                Math.max(0, after.articleIds().size() - before.articleIds().size()),
                Math.max(0, after.evidenceSentenceCount() - before.evidenceSentenceCount()),
                "추가 수집 %d건, 변경 후보 %d건".formatted(
                        write.observedArticleCount(), write.changedArticleCount()));
    }

    private InvestigationActionResult readFullText(Long runId,
                                                   InvestigationContext before,
                                                   Long articleId) {
        Set<Long> refreshedArticleIds = contentEnricher.enrichArticle(runId, articleId);
        if (!refreshedArticleIds.isEmpty()) {
            issueClusteringService.cluster(runId);
        }
        InvestigationContext after = analyzeChanges(
                runId, before, refreshedArticleIds, contextService.current(runId, before.issueId()));
        return new InvestigationActionResult(
                0,
                Math.max(0, after.evidenceSentenceCount() - before.evidenceSentenceCount()),
                "기사 #%d 전문 확보 시도".formatted(articleId));
    }

    private InvestigationContext analyzeChanges(Long runId,
                                                 InvestigationContext before,
                                                 Set<Long> refreshedArticleIds,
                                                 InvestigationContext clustered) {
        Set<Long> analysisTriggerIds = new LinkedHashSet<>(refreshedArticleIds);
        clustered.articleIds().stream()
                .filter(articleId -> !before.articleIds().contains(articleId))
                .forEach(analysisTriggerIds::add);
        if (analysisTriggerIds.isEmpty()) {
            return clustered;
        }
        analysisPipeline.analyzeInvestigation(runId, Set.copyOf(analysisTriggerIds));
        return contextService.current(runId, before.issueId());
    }

    private InvestigationActionResult compareHistory(InvestigationContext context,
                                                     AgentExploreResponse.Proposal proposal) {
        OffsetDateTime since = OffsetDateTime.now(ApiTimeZone.ZONE).minusDays(proposal.days());
        List<IssueArticle> candidates = issueArticleRepository
                .findRecentRepresentativesByTopicIdExcludingIssueId(
                        context.topicId(), context.issueId(), since);
        List<IssueArticle> matched = candidates.stream()
                .filter(value -> matchesAnyEntity(value, proposal.entities()))
                .toList();
        return new InvestigationActionResult(
                0,
                0,
                "최근 %d일의 관련 이슈 %d건 비교".formatted(proposal.days(), matched.size()));
    }

    private boolean matchesAnyEntity(IssueArticle candidate, List<String> entities) {
        String haystack = InvestigationQueryNormalizer.normalizeEntity(
                candidate.getIssue().getTitle() + " "
                + candidate.getIssue().getSummary() + " "
                + String.join(" ", candidate.getIssue().getEntities()));
        return entities.stream()
                .map(InvestigationQueryNormalizer::normalizeEntity)
                .anyMatch(value -> !value.isEmpty() && haystack.contains(value));
    }
}
