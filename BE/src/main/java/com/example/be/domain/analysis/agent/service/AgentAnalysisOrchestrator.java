package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceRequest;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceResponse;
import com.example.be.domain.analysis.agent.dto.AgentSelfCritiqueResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.AudienceRelevance;
import com.example.be.domain.analysis.entity.FindingAnalysisBullet;
import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import com.example.be.domain.analysis.entity.FindingCategory;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingPerspectiveTag;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.FindingSensitivityAxis;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisMetadata;
import com.example.be.domain.analysis.service.AnalysisResult;
import com.example.be.domain.analysis.service.AnalysisContext;
import com.example.be.domain.analysis.service.ArticleAnalysisOrchestrator;
import com.example.be.domain.analysis.service.FindingReuseCache;
import com.example.be.domain.analysis.service.FindingWriter;
import com.example.be.domain.analysis.service.StubArticleAnalyzer;
import com.example.be.domain.analysis.service.SensitivityCalculator;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.service.IssueCrossSourceWriter;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAnalysisOrchestrator implements ArticleAnalysisOrchestrator {

    private static final Set<String> GROUNDEDNESS_VALUES =
            Set.of("grounded", "weak", "ungrounded");
    private static final Set<String> CLAIM_TYPE_VALUES =
            Set.of("FACT", "FORECAST", "OPINION");
    private static final int AUDIENCE_COUNT = Audience.values().length;
    private static final int MAX_ARTICLE_TITLE_LENGTH = 1000;
    private static final int MAX_ARTICLE_SUMMARY_LENGTH = 2000;
    private static final int MAX_CANONICAL_URL_LENGTH = 2000;
    private static final int MAX_LANGUAGE_LENGTH = 10;
    private static final int MAX_TOPIC_NAME_LENGTH = 200;
    private static final int MAX_TOPIC_QUERY_LENGTH = 500;
    private static final int MAX_PUBLISHER_LENGTH = 500;
    private static final int MAX_ISSUE_MEMBERS = 10;

    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentRunRecorder recorder;
    private final StubArticleAnalyzer stubAnalyzer;
    private final AgentQuotaService quotaService;
    private final LlmPlanService planService;
    private final CollectionResultWriter resultWriter;
    private final IssueCrossSourceWriter crossSourceWriter;
    private final FindingWriter findingWriter;
    private final SensitivityCalculator sensitivityCalculator;

    @Override
    public AnalysisResult analyze(AnalysisContext context) {
        Long runId = context.runId();
        Article article = context.article();
        if (!properties.isEnabled()) {
            return stubAnalyzer.analyze(article);
        }

        ReservationSelection selection = reserve(runId, article.getId(), context.plan());
        if (selection == null) {
            return stubAnalyzer.analyze(article);
        }

        AgentAnalyzeRequest request = request(
                context, selection.plan(), selection.reservation().idempotencyKey());
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentAnalyzeResponse response;
        AnalysisResult result;
        List<IssueCrossSourceWriter.RuleStance> memberStances;
        try {
            response = client.analyze(request);
            result = toAnalysisResult(response);
            memberStances = validateIssueComparison(context, response);
            result = verifyEvidence(
                    runId, article.getId(), selection.plan(), result, false);
        } catch (RuntimeException exception) {
            AgentClientException clientException = exception instanceof AgentClientException value
                    ? value
                    : null;
            String code = clientException != null
                    ? clientException.getCode()
                    : "SCHEMA_VIOLATION";
            AgentClientException.Usage usage = failureUsage(clientException, selection.reservation());
            recordFailureSafely(
                    runId,
                    article.getId(),
                    request,
                    code,
                    exception.getMessage(),
                    usage,
                    timeoutPhase(clientException),
                    startedAt);
            completeFailureSafely(selection.reservation(), clientException, code);
            addAgentWarning(
                    runId,
                    CollectionRunWarning.CODE_LLM_ANALYSIS_FALLBACK,
                    "Agent 분석 실패로 일부 기사를 Stub 분석으로 대체했습니다. code=" + code);
            log.warn("Agent 분석에 실패해 Stub으로 대체한다. runId={} articleId={} code={} error={}",
                    runId, article.getId(), code, exception.getMessage());
            return stubAnalyzer.analyze(article);
        }

        recordSuccessSafely(runId, article.getId(), request, response, startedAt);
        completeSuccessSafely(selection.reservation(), response.meta().credits());
        result = selfCritiqueSafely(context, selection.plan(), result, response.crossSource());
        if (applyIssueComparisonSafely(context, response, memberStances)) {
            promoteConflictCandidateSafely(context, response, memberStances);
        }
        return result;
    }

    private AgentAnalyzeRequest request(AnalysisContext context,
                                        AgentPlan plan,
                                        String idempotencyKey) {
        Article article = context.article();
        Topic topic = article.getTopic();
        return new AgentAnalyzeRequest(
                idempotencyKey,
                plan,
                new AgentAnalyzeRequest.ArticlePayload(
                        article.getId(),
                        truncate(article.getTitle(), MAX_ARTICLE_TITLE_LENGTH),
                        truncate(article.getSummary(), MAX_ARTICLE_SUMMARY_LENGTH),
                        truncate(article.getCanonicalUrl(), MAX_CANONICAL_URL_LENGTH),
                        truncate(article.getLanguage(), MAX_LANGUAGE_LENGTH),
                        article.getPublishedAt(),
                        analysisText(article)),
                issueMembers(context).stream()
                        .map(member -> new AgentAnalyzeRequest.IssueMemberPayload(
                                member.getId(),
                                truncate(member.getTitle(), MAX_ARTICLE_TITLE_LENGTH),
                                truncate(member.getSummary(), MAX_ARTICLE_SUMMARY_LENGTH),
                                truncate(publisher(member), MAX_PUBLISHER_LENGTH)))
                        .toList(),
                new AgentAnalyzeRequest.TopicPayload(
                        truncate(topic.getName(), MAX_TOPIC_NAME_LENGTH),
                        truncate(topic.getQueryText(), MAX_TOPIC_QUERY_LENGTH),
                        listOrEmpty(topic.getRequiredKeywords()),
                        listOrEmpty(topic.getOptionalKeywords()),
                        listOrEmpty(topic.getExcludedKeywords())),
                null);
    }

    private AnalysisResult selfCritiqueSafely(AnalysisContext context,
                                               AgentPlan plan,
                                               AnalysisResult result,
                                               AgentAnalyzeResponse.CrossSource crossSource) {
        if (!context.selfCritiqueEligible()
                || !context.issue().present()
                || result.analysisSource() != AnalysisSource.LLM) {
            return result;
        }

        String idempotencyKey = "run:" + context.runId()
                + ":issue:" + context.issue().issueId() + ":self-critique";
        QuotaReservation reservation;
        try {
            reservation = quotaService.reserve(
                    context.runId(), idempotencyKey, AgentTask.SELF_CRITIQUE, plan);
        } catch (QuotaExceededException exception) {
            addAgentWarning(
                    context.runId(),
                    CollectionRunWarning.CODE_LLM_SELF_CRITIQUE_FAILED,
                    "자기 검증 quota가 부족해 최초 검증 결과를 유지했습니다.");
            return result;
        } catch (RuntimeException exception) {
            addAgentWarning(
                    context.runId(),
                    CollectionRunWarning.CODE_LLM_SELF_CRITIQUE_FAILED,
                    "자기 검증 quota 예약 실패로 최초 검증 결과를 유지했습니다.");
            log.warn("자기 검증 quota 예약에 실패했다. runId={} issueId={} error={}",
                    context.runId(), context.issue().issueId(), exception.getMessage(), exception);
            return result;
        }

        AgentAnalyzeRequest request;
        try {
            request = selfCritiqueRequest(context, plan, idempotencyKey, result, crossSource);
        } catch (RuntimeException exception) {
            completeFailureSafely(reservation, null, "SCHEMA_VIOLATION");
            addAgentWarning(
                    context.runId(),
                    CollectionRunWarning.CODE_LLM_SELF_CRITIQUE_FAILED,
                    "자기 검증 요청 구성 실패로 최초 검증 결과를 유지했습니다.");
            return result;
        }
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        try {
            AgentSelfCritiqueResponse response = client.selfCritique(request);
            AnalysisResult revised = toSelfCritiquedResult(result, response);
            recordSelfCritiqueSuccessSafely(
                    context.runId(), context.issue().issueId(), request, response, startedAt);
            completeSuccessSafely(
                    reservation,
                    response.meta().credits(),
                    selfCritiqueProviderInvoked(response.meta()));
            return revised;
        } catch (RuntimeException exception) {
            AgentClientException clientException = exception instanceof AgentClientException value
                    ? value
                    : null;
            String code = clientException == null ? "SCHEMA_VIOLATION" : clientException.getCode();
            recordSelfCritiqueFailureSafely(
                    context.runId(),
                    context.issue().issueId(),
                    request,
                    code,
                    exception.getMessage(),
                    failureUsage(clientException, reservation),
                    timeoutPhase(clientException),
                    startedAt);
            completeFailureSafely(reservation, clientException, code);
            addAgentWarning(
                    context.runId(),
                    CollectionRunWarning.CODE_LLM_SELF_CRITIQUE_FAILED,
                    "자기 검증 실패로 최초 검증 결과를 유지했습니다. code=" + code);
            log.warn("자기 검증에 실패해 최초 검증 결과를 유지한다. runId={} issueId={} code={} error={}",
                    context.runId(), context.issue().issueId(), code, exception.getMessage());
            return result;
        }
    }

    private AgentAnalyzeRequest selfCritiqueRequest(
            AnalysisContext context,
            AgentPlan plan,
            String idempotencyKey,
            AnalysisResult result,
            AgentAnalyzeResponse.CrossSource crossSource) {
        AgentAnalyzeRequest base = request(context, plan, idempotencyKey);
        AgentAnalyzeRequest.PreviousFindingPayload previous =
                new AgentAnalyzeRequest.PreviousFindingPayload(
                        result.summary(),
                        toAgentSensitivity(result.sensitivity()),
                        result.analysisSections().stream()
                                .map(section -> new AgentAnalyzeRequest.PreviousSectionPayload(
                                        section.heading(),
                                        section.bullets().stream()
                                                .map(this::toPreviousBullet)
                                                .toList()))
                                .toList(),
                        crossSource);
        return new AgentAnalyzeRequest(
                base.idempotencyKey(),
                base.plan(),
                base.article(),
                base.issueMembers(),
                base.topic(),
                previous,
                true);
    }

    private AgentAnalyzeRequest.PreviousBulletPayload toPreviousBullet(
            FindingAnalysisBullet bullet) {
        return new AgentAnalyzeRequest.PreviousBulletPayload(
                bullet.text(),
                bullet.evidence().stream().map(index -> index + 1).toList(),
                bullet.groundedness(),
                bullet.confidence(),
                bullet.groundingReason(),
                bullet.claimType(),
                bullet.attributedTo());
    }

    private AnalysisResult toSelfCritiquedResult(AnalysisResult original,
                                                 AgentSelfCritiqueResponse response) {
        if (response == null || response.sections() == null || response.meta() == null
                || !StringUtils.hasText(response.summaryKo())
                || response.summaryKo().length() < 10 || response.summaryKo().length() > 120
                || response.targetClaimCount() == null
                || response.revisedClaimCount() == null
                || response.targetClaimCount() < 0 || response.targetClaimCount() > 1
                || response.revisedClaimCount() < 0
                || response.revisedClaimCount() > response.targetClaimCount()
                || response.unsupportedExpressions() == null
                || response.unsupportedExpressions().size() > 3
                || response.unsupportedExpressions().stream()
                .anyMatch(value -> !StringUtils.hasText(value) || value.length() > 500)) {
            throw schemaViolation("Agent 자기 검증 응답의 필수 필드가 올바르지 않습니다.");
        }
        validateSelfCritiqueMeta(response.meta());
        if (response.sections().size() != original.analysisSections().size()) {
            throw schemaViolation("Agent 자기 검증 section 수가 기존 결과와 다릅니다.");
        }

        int changedBullets = 0;
        List<FindingAnalysisSection> sections = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < response.sections().size(); sectionIndex++) {
            AgentSelfCritiqueResponse.Section responseSection = response.sections().get(sectionIndex);
            FindingAnalysisSection originalSection = original.analysisSections().get(sectionIndex);
            if (responseSection == null
                    || !originalSection.heading().equals(responseSection.heading())
                    || responseSection.bullets() == null
                    || responseSection.bullets().size() != originalSection.bullets().size()) {
                throw schemaViolation("Agent 자기 검증 section 계약이 기존 결과와 다릅니다.");
            }
            List<FindingAnalysisBullet> bullets = new ArrayList<>();
            for (int bulletIndex = 0; bulletIndex < responseSection.bullets().size(); bulletIndex++) {
                FindingAnalysisBullet originalBullet = originalSection.bullets().get(bulletIndex);
                FindingAnalysisBullet bullet = toSelfCritiquedBullet(
                        responseSection.bullets().get(bulletIndex),
                        originalBullet,
                        original.sections().size());
                if (!bullet.equals(originalBullet)) {
                    changedBullets++;
                }
                bullets.add(bullet);
            }
            sections.add(new FindingAnalysisSection(originalSection.heading(), bullets));
        }
        boolean summaryChanged = !response.summaryKo().trim().equals(original.summary());
        if (summaryChanged
                || changedBullets > 1
                || (response.revisedClaimCount() == 0 && changedBullets > 0)
                || (response.revisedClaimCount() == 1 && changedBullets == 0)) {
            throw schemaViolation("Agent 자기 검증은 대상 주장 한 건만 수정할 수 있습니다.");
        }
        return withSelfCritique(
                original,
                original.summary(),
                List.copyOf(sections));
    }

    private FindingAnalysisBullet toSelfCritiquedBullet(
            AgentSelfCritiqueResponse.Bullet response,
            FindingAnalysisBullet original,
            int sentenceCount) {
        if (response == null || !StringUtils.hasText(response.text())
                || response.text().length() > 80
                || response.evidenceSentenceIds() == null
                || !GROUNDEDNESS_VALUES.contains(response.groundedness())
                || response.confidence() == null
                || response.confidence().signum() < 0
                || response.confidence().compareTo(BigDecimal.ONE) > 0
                || !StringUtils.hasText(response.groundingReason())
                || response.groundingReason().length() > 1000
                || !original.claimType().equals(response.claimType())
                || !Objects.equals(original.attributedTo(), response.attributedTo())) {
            throw schemaViolation("Agent 자기 검증 bullet 계약이 올바르지 않습니다.");
        }
        boolean unsupported = "ungrounded".equals(response.groundedness());
        if (response.evidenceSentenceIds().size()
                != new HashSet<>(response.evidenceSentenceIds()).size()
                || (unsupported && !response.evidenceSentenceIds().isEmpty())
                || (!unsupported && response.evidenceSentenceIds().isEmpty())
                || (unsupported && response.confidence().signum() != 0)) {
            throw schemaViolation("Agent 자기 검증 evidence 계약이 올바르지 않습니다.");
        }
        List<Integer> publicEvidence = toPublicEvidenceIndexes(
                response.evidenceSentenceIds(), sentenceCount);
        if (!original.evidence().containsAll(publicEvidence)) {
            throw schemaViolation("Agent 자기 검증은 새로운 근거 문장을 추가할 수 없습니다.");
        }
        return new FindingAnalysisBullet(
                response.text().trim(),
                publicEvidence,
                response.groundedness(),
                response.confidence(),
                response.groundingReason().trim(),
                response.claimType(),
                response.attributedTo());
    }

    private AnalysisResult withSelfCritique(AnalysisResult original,
                                            String summary,
                                            List<FindingAnalysisSection> sections) {
        List<FindingKeyPoint> keyPoints = sections.stream()
                .flatMap(section -> section.bullets().stream())
                .map(bullet -> new FindingKeyPoint(
                        bullet.text(),
                        bullet.evidence(),
                        bullet.groundedness(),
                        bullet.groundingReason(),
                        bullet.claimType(),
                        bullet.attributedTo()))
                .toList();
        return new AnalysisResult(
                summary,
                keyPoints,
                original.intent(),
                original.sentiment(),
                original.sensitivity(),
                original.relevance(),
                original.category(),
                original.sections(),
                original.analysisSource(),
                sections,
                original.entities(),
                original.perspectiveTags(),
                original.metadata());
    }

    private void validateSelfCritiqueMeta(AgentAnalyzeResponse.Meta meta) {
        if (!StringUtils.hasText(meta.provider())
                || !StringUtils.hasText(meta.model())
                || !StringUtils.hasText(meta.promptVersion())
                || isNegative(meta.inputTokens())
                || isNegative(meta.outputTokens())
                || isNegative(meta.costUsd())
                || isNegative(meta.credits())) {
            throw schemaViolation("Agent 자기 검증 meta가 올바르지 않습니다.");
        }
        if (!selfCritiqueProviderInvoked(meta)
                && (meta.inputTokens() != 0L
                || meta.outputTokens() != 0L
                || meta.costUsd().compareTo(BigDecimal.ZERO) != 0
                || meta.credits().compareTo(BigDecimal.ZERO) != 0)) {
            throw schemaViolation("Agent provider 미호출 자기 검증 meta가 올바르지 않습니다.");
        }
    }

    private boolean selfCritiqueProviderInvoked(AgentAnalyzeResponse.Meta meta) {
        return !meta.mock() && !meta.promptVersion().startsWith("self-critique.rules.");
    }

    private List<Article> issueMembers(AnalysisContext context) {
        return context.issue().membersExcept(context.article().getId()).stream()
                .limit(MAX_ISSUE_MEMBERS)
                .toList();
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    private String publisher(Article article) {
        if (StringUtils.hasText(article.getSourceName())) {
            return article.getSourceName().trim();
        }
        if (article.getSource() != null && StringUtils.hasText(article.getSource().getName())) {
            return article.getSource().getName().trim();
        }
        return "Unknown";
    }

    private List<IssueCrossSourceWriter.RuleStance> validateIssueComparison(
            AnalysisContext context,
            AgentAnalyzeResponse response) {
        AgentAnalyzeResponse.CrossSource crossSource = response.crossSource();
        if (crossSource == null || response.promoteCandidates() == null
                || response.memberStances() == null) {
            throw schemaViolation("Agent 이슈 교차 비교 응답의 필수 필드가 없습니다.");
        }
        if (!context.issue().present()) {
            if (!crossSource.consensus().isEmpty()
                    || !crossSource.soleSource().isEmpty()
                    || !crossSource.conflicts().isEmpty()
                    || !crossSource.missingStakeholders().isEmpty()
                    || !response.promoteCandidates().isEmpty()
                    || !response.memberStances().isEmpty()) {
                throw schemaViolation("이슈 문맥이 없는 분석은 교차 비교 결과가 비어야 합니다.");
            }
            return List.of();
        }

        Set<Long> articleIds = context.issue().articles().stream()
                .map(Article::getId)
                .collect(Collectors.toSet());
        validateObservationTexts(crossSource.consensus(), "consensus");
        validateObservationTexts(crossSource.missingStakeholders(), "missingStakeholders");
        crossSource.soleSource().forEach(observation -> {
            if (observation == null || !articleIds.contains(observation.articleId())
                    || !StringUtils.hasText(observation.text())) {
                throw schemaViolation("Agent soleSource가 이슈 기사를 올바르게 참조하지 않습니다.");
            }
        });
        Set<Long> conflictArticleIds = new HashSet<>();
        crossSource.conflicts().forEach(observation -> {
            if (observation == null || observation.articleIds().size() < 2
                    || observation.articleIds().size() != new HashSet<>(observation.articleIds()).size()
                    || !articleIds.containsAll(observation.articleIds())
                    || !StringUtils.hasText(observation.text())) {
                throw schemaViolation("Agent conflicts가 이슈 기사를 올바르게 참조하지 않습니다.");
            }
            conflictArticleIds.addAll(observation.articleIds());
        });

        List<Long> expectedMemberIds = issueMembers(context).stream()
                .map(Article::getId)
                .toList();
        if (response.memberStances().size() != expectedMemberIds.size()) {
            throw schemaViolation("Agent memberStances의 기사 수가 일치하지 않습니다.");
        }
        Set<Long> stanceIds = new HashSet<>();
        List<IssueCrossSourceWriter.RuleStance> stances = response.memberStances().stream()
                .map(value -> toRuleStance(value, expectedMemberIds, stanceIds))
                .toList();
        if (stanceIds.size() != expectedMemberIds.size()) {
            throw schemaViolation("Agent memberStances가 이슈 멤버와 일치하지 않습니다.");
        }

        if (response.promoteCandidates().size() > 1
                || response.promoteCandidates().size()
                != new HashSet<>(response.promoteCandidates()).size()) {
            throw schemaViolation("Agent promoteCandidates는 이슈당 최대 1건입니다.");
        }
        response.promoteCandidates().forEach(articleId -> {
            if (!expectedMemberIds.contains(articleId) || !conflictArticleIds.contains(articleId)) {
                throw schemaViolation("Agent 승격 후보는 충돌 멤버 기사여야 합니다.");
            }
        });
        return stances;
    }

    private IssueCrossSourceWriter.RuleStance toRuleStance(
            AgentAnalyzeResponse.MemberStance value,
            List<Long> expectedMemberIds,
            Set<Long> stanceIds) {
        if (value == null || !expectedMemberIds.contains(value.articleId())
                || !stanceIds.add(value.articleId()) || value.confidence() == null
                || value.confidence().signum() < 0
                || value.confidence().compareTo(BigDecimal.ONE) > 0) {
            throw schemaViolation("Agent memberStance가 올바르지 않습니다.");
        }
        try {
            return new IssueCrossSourceWriter.RuleStance(
                    value.articleId(), IssueStance.valueOf(value.stance()), value.confidence());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw schemaViolation("Agent memberStance 값이 올바르지 않습니다.");
        }
    }

    private void validateObservationTexts(List<String> values, String field) {
        if (values.stream().anyMatch(value -> !StringUtils.hasText(value))) {
            throw schemaViolation("Agent " + field + " 값이 올바르지 않습니다.");
        }
    }

    private boolean applyIssueComparisonSafely(
            AnalysisContext context,
            AgentAnalyzeResponse response,
            List<IssueCrossSourceWriter.RuleStance> memberStances) {
        if (!context.issue().present()) {
            return false;
        }
        try {
            crossSourceWriter.applyRepresentative(
                    context.issue().issueId(),
                    toIssueCrossSource(response.crossSource()),
                    memberStances,
                    !response.meta().mock());
            return true;
        } catch (RuntimeException exception) {
            addAgentWarning(
                    context.runId(),
                    CollectionRunWarning.CODE_LLM_CROSS_SOURCE_FAILED,
                    "이슈 교차 비교 결과를 저장하지 못했습니다.");
            log.warn("이슈 교차 비교 결과 저장에 실패했다. runId={} issueId={} error={}",
                    context.runId(), context.issue().issueId(), exception.getMessage(), exception);
            return false;
        }
    }

    private void promoteConflictCandidateSafely(
            AnalysisContext context,
            AgentAnalyzeResponse representativeResponse,
            List<IssueCrossSourceWriter.RuleStance> memberStances) {
        try {
            promoteConflictCandidate(context, representativeResponse, memberStances);
        } catch (RuntimeException exception) {
            addAgentWarning(
                    context.runId(),
                    CollectionRunWarning.CODE_LLM_CROSS_SOURCE_FAILED,
                    "충돌 기사 승격 처리에 실패했습니다.");
            log.warn("충돌 기사 승격 처리에 실패했다. runId={} issueId={} error={}",
                    context.runId(), context.issue().issueId(), exception.getMessage(), exception);
        }
    }

    private void promoteConflictCandidate(
            AnalysisContext context,
            AgentAnalyzeResponse representativeResponse,
            List<IssueCrossSourceWriter.RuleStance> memberStances) {
        if (representativeResponse.promoteCandidates().isEmpty()) {
            return;
        }
        Long articleId = representativeResponse.promoteCandidates().getFirst();
        // 같은 run의 대표 분석 대상은 파이프라인 결과가 우선한다. 승격 finding과 먼저 쓴 쪽이
        // 우연히 이기는 순서 의존성을 만들지 않는다.
        if (context.issue().primaryTargetArticleIds().contains(articleId)) {
            return;
        }
        Article article = context.issue().article(articleId);
        IssueCrossSourceWriter.RuleStance stance = memberStances.stream()
                .filter(value -> value.articleId().equals(articleId))
                .findFirst()
                .orElseThrow();
        // 승격 호출은 해당 기사 자체만 분석한다. 이슈 멤버를 다시 보내 교차 비교를 재귀 실행하지 않는다.
        AnalysisContext promotedContext = new AnalysisContext(
                context.runId(), article, context.plan());
        String idempotencyKey = "run:" + context.runId()
                + ":issue:" + context.issue().issueId()
                + ":promotion:article:" + articleId;
        ReservationSelection selection = reserve(
                context.runId(), articleId, context.plan(), idempotencyKey);
        if (selection == null) {
            return;
        }

        AgentAnalyzeRequest request = request(
                promotedContext, selection.plan(), selection.reservation().idempotencyKey());
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentAnalyzeResponse response;
        AnalysisResult result;
        try {
            response = client.analyze(request);
            result = toAnalysisResult(response);
            validateIssueComparison(promotedContext, response);
            result = verifyEvidence(
                    context.runId(), articleId, selection.plan(), result, true);
        } catch (RuntimeException exception) {
            handlePromotionFailure(context, articleId, request, selection, startedAt, exception);
            return;
        }

        recordSuccessSafely(context.runId(), articleId, request, response, startedAt);
        completeSuccessSafely(selection.reservation(), response.meta().credits());
        try {
            findingWriter.write(
                    context.runId(),
                    articleId,
                    ChangeType.UPDATED,
                    FindingReuseCache.inputHash(promotedContext),
                    result);
            crossSourceWriter.confirmPromotion(
                    context.issue().issueId(),
                    articleId,
                    stance.stance(),
                    stance.confidence(),
                    !response.meta().mock());
        } catch (RuntimeException exception) {
            addAgentWarning(
                    context.runId(),
                    CollectionRunWarning.CODE_LLM_CROSS_SOURCE_FAILED,
                    "충돌 기사 분석 결과를 저장하지 못했습니다. articleId=" + articleId);
            log.warn("충돌 기사 승격 결과 저장에 실패했다. runId={} issueId={} articleId={} error={}",
                    context.runId(), context.issue().issueId(), articleId, exception.getMessage(), exception);
        }
    }

    private IssueCrossSource toIssueCrossSource(AgentAnalyzeResponse.CrossSource source) {
        return new IssueCrossSource(
                source.consensus(),
                source.soleSource().stream()
                        .map(value -> new IssueCrossSource.SoleSource(
                                value.articleId(), value.text()))
                        .toList(),
                source.conflicts().stream()
                        .map(value -> new IssueCrossSource.Conflict(
                                value.articleIds(), value.text()))
                        .toList(),
                source.missingStakeholders());
    }

    private void handlePromotionFailure(
            AnalysisContext context,
            Long articleId,
            AgentAnalyzeRequest request,
            ReservationSelection selection,
            LocalDateTime startedAt,
            RuntimeException exception) {
        AgentClientException clientException = exception instanceof AgentClientException value
                ? value
                : null;
        String code = clientException == null ? "SCHEMA_VIOLATION" : clientException.getCode();
        AgentClientException.Usage usage = failureUsage(clientException, selection.reservation());
        recordFailureSafely(
                context.runId(), articleId, request, code, exception.getMessage(), usage,
                timeoutPhase(clientException), startedAt);
        completeFailureSafely(selection.reservation(), clientException, code);
        addAgentWarning(
                context.runId(),
                CollectionRunWarning.CODE_LLM_CROSS_SOURCE_FAILED,
                "충돌 기사 추가 분석에 실패했습니다. articleId=" + articleId);
        log.warn("충돌 기사 승격 분석에 실패했다. runId={} issueId={} articleId={} error={}",
                context.runId(), context.issue().issueId(), articleId, exception.getMessage(), exception);
    }

    private ReservationSelection reserve(Long runId, Long articleId, AgentPlan requestedPlan) {
        return reserve(runId, articleId, requestedPlan, "run:" + runId + ":article:" + articleId);
    }

    private ReservationSelection reserve(Long runId,
                                         Long articleId,
                                         AgentPlan requestedPlan,
                                         String idempotencyKey) {
        try {
            return new ReservationSelection(
                    requestedPlan,
                    quotaService.reserve(runId, idempotencyKey, AgentTask.ANALYZE, requestedPlan));
        } catch (QuotaExceededException exhausted) {
            if (requestedPlan == AgentPlan.PAID
                    && planService.paidExhaustedAction() == PaidExhaustedAction.FALLBACK_FREE) {
                try {
                    QuotaReservation reservation = quotaService.reserve(
                            runId, idempotencyKey + ":fallback-free", AgentTask.ANALYZE, AgentPlan.FREE);
                    addAgentWarning(runId,
                            CollectionRunWarning.CODE_LLM_FALLBACK_FREE,
                            "PAID quota가 소진되어 일부 기사를 FREE 플랜으로 분석했습니다.");
                    return new ReservationSelection(AgentPlan.FREE, reservation);
                } catch (QuotaExceededException freeExhausted) {
                    log.warn("FREE fallback quota도 소진됐다. runId={} articleId={}", runId, articleId);
                }
            }
            addAgentWarning(runId,
                    CollectionRunWarning.CODE_LLM_QUOTA_EXHAUSTED,
                    "LLM quota가 소진되어 일부 기사를 Stub으로 분석했습니다.");
            return null;
        }
    }

    private void addAgentWarning(Long runId, String code, String message) {
        try {
            resultWriter.addAgentWarning(runId, code, message);
        } catch (RuntimeException exception) {
            log.error("LLM quota 경고를 기록하지 못했다. runId={} code={}", runId, code, exception);
        }
    }

    private void completeSuccessSafely(QuotaReservation reservation, BigDecimal credits) {
        completeSuccessSafely(reservation, credits, true);
    }

    private void completeSuccessSafely(QuotaReservation reservation,
                                       BigDecimal credits,
                                       boolean providerInvoked) {
        try {
            quotaService.completeSuccess(reservation, credits, providerInvoked);
        } catch (RuntimeException exception) {
            log.error("Agent 성공 quota 예약을 정산하지 못했다. reservationId={}",
                    reservation.id(), exception);
        }
    }

    private void completeFailureSafely(QuotaReservation reservation,
                                       AgentClientException exception,
                                       String code) {
        try {
            if (exception == null) {
                quotaService.completeFailure(reservation, code);
            } else {
                quotaService.completeFailure(reservation, exception);
            }
        } catch (RuntimeException completionError) {
            log.error("Agent 실패 quota 예약을 정산하지 못했다. reservationId={}",
                    reservation.id(), completionError);
        }
    }

    private AgentClientException.Usage failureUsage(AgentClientException exception,
                                                     QuotaReservation reservation) {
        if (exception == null || exception.getUsage() != null || !exception.isReadTimeout()) {
            return exception == null ? null : exception.getUsage();
        }
        return new AgentClientException.Usage(null, null, null, reservation.reservedUnits());
    }

    private AgentTimeoutPhase timeoutPhase(AgentClientException exception) {
        if (exception == null || exception.getTimeoutPhase() == AgentClientException.TimeoutPhase.NONE) {
            return null;
        }
        return AgentTimeoutPhase.valueOf(exception.getTimeoutPhase().name());
    }

    private AnalysisResult toAnalysisResult(AgentAnalyzeResponse response) {
        if (response.sentences() == null || response.sentences().isEmpty()
                || response.sections() == null || response.classification() == null
                || response.meta() == null || !StringUtils.hasText(response.summaryKo())
                || response.sentences().stream().anyMatch(sentence -> !StringUtils.hasText(sentence))) {
            throw new AgentClientException("SCHEMA_VIOLATION", "Agent 분석 응답의 필수 필드가 없습니다.");
        }

        List<String> sentenceTexts = response.sentences().stream()
                .map(String::trim)
                .toList();
        List<FindingSection> sentences = IntStream.range(0, sentenceTexts.size())
                .mapToObj(index -> new FindingSection(index, sentenceTexts.get(index)))
                .toList();

        List<FindingAnalysisSection> analysisSections = response.sections().stream()
                .map(section -> toAnalysisSection(section, sentences.size()))
                .toList();
        if (analysisSections.isEmpty()) {
            throw schemaViolation("Agent 분석 응답에 analysis section이 없습니다.");
        }
        List<FindingKeyPoint> keyPoints = analysisSections.stream()
                .flatMap(section -> section.bullets().stream())
                .map(bullet -> new FindingKeyPoint(
                        bullet.text(),
                        bullet.evidence(),
                        bullet.groundedness(),
                        bullet.groundingReason(),
                        bullet.claimType(),
                        bullet.attributedTo()))
                .toList();
        AgentAnalyzeResponse.Classification classification = response.classification();
        if (!StringUtils.hasText(classification.intent())) {
            throw schemaViolation("Agent classification intent가 없습니다.");
        }
        validateCategory(classification.category());
        FindingEntities entities = toEntities(response.entities());
        List<FindingPerspectiveTag> perspectiveTags = toPerspectiveTags(
                response.perspectiveTags(), sentences.size());
        AnalysisMetadata metadata = toMetadata(response.meta());
        return new AnalysisResult(
                response.summaryKo().trim(),
                keyPoints,
                classification.intent().trim(),
                Sentiment.fromApiValue(classification.sentiment()),
                toSensitivity(classification.sensitivity(), sentences.size()),
                Relevance.fromApiValue(classification.relevance()),
                classification.category(),
                sentences,
                response.meta().mock() ? AnalysisSource.STUB : AnalysisSource.LLM,
                analysisSections,
                entities,
                perspectiveTags,
                metadata);
    }

    private FindingSensitivity toSensitivity(AgentAnalyzeResponse.Sensitivity sensitivity,
                                             int sentenceCount) {
        if (sensitivity == null) {
            throw schemaViolation("Agent classification sensitivity가 없습니다.");
        }
        return sensitivityCalculator.calculate(
                toSensitivityAxis("customerMove", sensitivity.customerMove(), sentenceCount),
                toSensitivityAxis("dealSignal", sensitivity.dealSignal(), sentenceCount),
                toSensitivityAxis("competitorThreat", sensitivity.competitorThreat(), sentenceCount),
                toSensitivityAxis("industryShift", sensitivity.industryShift(), sentenceCount));
    }

    private FindingSensitivityAxis toSensitivityAxis(String name,
                                                      AgentAnalyzeResponse.SensitivityAxis axis,
                                                      int sentenceCount) {
        if (axis == null) {
            throw schemaViolation("Agent sensitivity." + name + "이 없습니다.");
        }
        List<Integer> agentEvidence = listOrEmpty(axis.evidenceSentenceIds());
        if (agentEvidence.stream().anyMatch(id -> id == null || id < 1 || id > sentenceCount)) {
            throw schemaViolation("Agent sensitivity." + name + " 근거 문장 ID가 범위를 벗어났습니다.");
        }
        try {
            return new FindingSensitivityAxis(
                    axis.score(),
                    agentEvidence.stream().map(id -> id - 1).distinct().toList());
        } catch (IllegalArgumentException exception) {
            throw schemaViolation("Agent sensitivity." + name + " 형식이 올바르지 않습니다.");
        }
    }

    private AgentAnalyzeResponse.Sensitivity toAgentSensitivity(FindingSensitivity sensitivity) {
        return new AgentAnalyzeResponse.Sensitivity(
                toAgentSensitivityAxis(sensitivity.customerMove()),
                toAgentSensitivityAxis(sensitivity.dealSignal()),
                toAgentSensitivityAxis(sensitivity.competitorThreat()),
                toAgentSensitivityAxis(sensitivity.industryShift()));
    }

    private AgentAnalyzeResponse.SensitivityAxis toAgentSensitivityAxis(FindingSensitivityAxis axis) {
        return new AgentAnalyzeResponse.SensitivityAxis(
                axis.score(),
                axis.evidenceSentenceIds().stream().map(id -> id + 1).toList());
    }

    private AnalysisResult verifyEvidence(Long runId,
                                          Long articleId,
                                          AgentPlan plan,
                                          AnalysisResult result,
                                          boolean promotion) {
        if (result.analysisSource() != AnalysisSource.LLM) {
            return result;
        }
        List<EvidenceClaimTarget> targets = evidenceTargets(result.analysisSections());
        if (targets.isEmpty()) {
            return withVerifiedSections(result, unsupportedSections(result.analysisSections()));
        }

        String idempotencyKey = "run:" + runId + ":article:" + articleId
                + (promotion ? ":promotion" : "") + ":evidence";
        QuotaReservation evidenceReservation;
        try {
            evidenceReservation = quotaService.reserve(
                    runId, idempotencyKey, AgentTask.VERIFY_EVIDENCE, plan);
        } catch (QuotaExceededException exception) {
            addAgentWarning(
                    runId,
                    CollectionRunWarning.CODE_LLM_EVIDENCE_VERIFICATION_FAILED,
                    "근거 검증 quota가 부족해 해당 기사의 주장을 보고서 근거에서 제외했습니다.");
            log.warn("근거 검증 quota가 부족해 기사 주장을 제외한다. runId={} articleId={}",
                    runId, articleId);
            return withVerifiedSections(result, unsupportedSections(result.analysisSections()));
        }

        AgentEvidenceRequest request = evidenceRequest(
                idempotencyKey, plan, targets, result.sections());
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        List<FindingAnalysisSection> verifiedSections;
        try {
            AgentEvidenceResponse response = client.verifyEvidence(request);
            verifiedSections = verifiedSections(
                    result.analysisSections(), response, result.sections().size(), request);
            recordEvidenceSuccessSafely(
                    runId, articleId, request, response, startedAt);
            completeSuccessSafely(
                    evidenceReservation,
                    response.meta().credits(),
                    evidenceProviderInvoked(response.meta()));
        } catch (RuntimeException exception) {
            AgentClientException clientException = exception instanceof AgentClientException value
                    ? value
                    : null;
            String code = clientException == null ? "SCHEMA_VIOLATION" : clientException.getCode();
            recordEvidenceFailureSafely(
                    runId,
                    articleId,
                    request,
                    code,
                    exception.getMessage(),
                    failureUsage(clientException, evidenceReservation),
                    timeoutPhase(clientException),
                    startedAt);
            completeFailureSafely(evidenceReservation, clientException, code);
            addAgentWarning(
                    runId,
                    CollectionRunWarning.CODE_LLM_EVIDENCE_VERIFICATION_FAILED,
                    "Agent 근거 배치 검증 실패로 해당 기사의 주장을 보고서 근거에서 제외했습니다. code="
                            + code);
            log.warn("Agent 근거 배치 검증 실패로 기사 주장을 제외한다. "
                            + "runId={} articleId={} code={} error={}",
                    runId, articleId, code, exception.getMessage());
            verifiedSections = unsupportedSections(result.analysisSections());
        }
        return withVerifiedSections(result, verifiedSections);
    }

    private AnalysisResult withVerifiedSections(AnalysisResult result,
                                                List<FindingAnalysisSection> verifiedSections) {
        List<FindingKeyPoint> keyPoints = verifiedSections.stream()
                .flatMap(section -> section.bullets().stream())
                .map(bullet -> new FindingKeyPoint(
                        bullet.text(),
                        bullet.evidence(),
                        bullet.groundedness(),
                        bullet.groundingReason(),
                        bullet.claimType(),
                        bullet.attributedTo()))
                .toList();
        return new AnalysisResult(
                result.summary(),
                keyPoints,
                result.intent(),
                result.sentiment(),
                result.sensitivity(),
                result.relevance(),
                result.category(),
                result.sections(),
                result.analysisSource(),
                verifiedSections,
                result.entities(),
                result.perspectiveTags(),
                result.metadata());
    }

    private List<EvidenceClaimTarget> evidenceTargets(List<FindingAnalysisSection> sections) {
        List<EvidenceClaimTarget> targets = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            List<FindingAnalysisBullet> bullets = sections.get(sectionIndex).bullets();
            for (int bulletIndex = 0; bulletIndex < bullets.size(); bulletIndex++) {
                FindingAnalysisBullet bullet = bullets.get(bulletIndex);
                if (!"ungrounded".equals(bullet.groundedness())) {
                    targets.add(new EvidenceClaimTarget(
                            sectionIndex + ":" + bulletIndex, bullet));
                }
            }
        }
        return List.copyOf(targets);
    }

    private AgentEvidenceRequest evidenceRequest(String idempotencyKey,
                                                 AgentPlan plan,
                                                 List<EvidenceClaimTarget> targets,
                                                 List<FindingSection> sentences) {
        return new AgentEvidenceRequest(
                idempotencyKey,
                plan,
                targets.stream()
                        .map(target -> new AgentEvidenceRequest.ClaimPayload(
                                target.claimId(),
                                target.bullet().text(),
                                target.bullet().claimType(),
                                target.bullet().attributedTo(),
                                target.bullet().evidence().stream()
                                        .map(index -> new AgentEvidenceRequest.SentencePayload(
                                                index + 1, sentences.get(index).text()))
                                        .toList()))
                        .toList());
    }

    private List<FindingAnalysisSection> verifiedSections(
            List<FindingAnalysisSection> sections,
            AgentEvidenceResponse response,
            int sentenceCount,
            AgentEvidenceRequest request) {
        if (response == null || response.results() == null || response.meta() == null) {
            throw schemaViolation("Agent 근거 배치 검증 응답의 필수 필드가 없습니다.");
        }
        validateEvidenceMeta(response.meta());
        Map<String, AgentEvidenceResponse.Result> resultById = response.results().stream()
                .collect(Collectors.toMap(AgentEvidenceResponse.Result::claimId, value -> value,
                        (left, right) -> {
                            throw schemaViolation("Agent 근거 배치 검증 claimId가 중복되었습니다.");
                        }));
        Map<String, AgentEvidenceRequest.ClaimPayload> requestById = request.claims().stream()
                .collect(Collectors.toMap(AgentEvidenceRequest.ClaimPayload::claimId,
                        value -> value));
        if (!resultById.keySet().equals(requestById.keySet())) {
            throw schemaViolation("Agent 근거 배치 검증 결과가 요청 claim과 일치하지 않습니다.");
        }

        return IntStream.range(0, sections.size())
                .mapToObj(sectionIndex -> {
                    FindingAnalysisSection section = sections.get(sectionIndex);
                    List<FindingAnalysisBullet> bullets = IntStream.range(
                                    0, section.bullets().size())
                            .mapToObj(bulletIndex -> {
                                FindingAnalysisBullet bullet = section.bullets().get(bulletIndex);
                                if ("ungrounded".equals(bullet.groundedness())) {
                                    return unsupportedBullet(bullet);
                                }
                                String claimId = sectionIndex + ":" + bulletIndex;
                                return verifiedBullet(
                                        bullet,
                                        resultById.get(claimId),
                                        sentenceCount,
                                        requestById.get(claimId));
                            })
                            .toList();
                    return new FindingAnalysisSection(section.heading(), bullets);
                })
                .toList();
    }

    private FindingAnalysisBullet verifiedBullet(FindingAnalysisBullet bullet,
                                                  AgentEvidenceResponse.Result response,
                                                  int sentenceCount,
                                                  AgentEvidenceRequest.ClaimPayload request) {
        if (response == null
                || !GROUNDEDNESS_VALUES.contains(response.status())
                || !StringUtils.hasText(response.reason())
                || response.acceptedSentenceIds() == null) {
            throw schemaViolation("Agent 근거 검증 응답의 필수 필드가 없습니다.");
        }
        Set<Integer> acceptedIds = new HashSet<>(response.acceptedSentenceIds());
        Set<Integer> requestedIds = request.sentences().stream()
                .map(AgentEvidenceRequest.SentencePayload::id)
                .collect(Collectors.toSet());
        boolean unsupported = "ungrounded".equals(response.status());
        if (acceptedIds.size() != response.acceptedSentenceIds().size()
                || !requestedIds.containsAll(acceptedIds)
                || (unsupported && !acceptedIds.isEmpty())
                || (!unsupported && acceptedIds.isEmpty())) {
            throw schemaViolation("Agent 근거 검증 응답의 sentence id 계약이 올바르지 않습니다.");
        }
        return new FindingAnalysisBullet(
                bullet.text(),
                toPublicEvidenceIndexes(response.acceptedSentenceIds(), sentenceCount),
                response.status(),
                unsupported ? BigDecimal.ZERO : bullet.confidence(),
                response.reason().trim(),
                bullet.claimType(),
                bullet.attributedTo());
    }

    private void validateEvidenceMeta(AgentEvidenceResponse.Meta meta) {
        if (!StringUtils.hasText(meta.provider())
                || !StringUtils.hasText(meta.model())
                || !StringUtils.hasText(meta.promptVersion())
                || isNegative(meta.inputTokens())
                || isNegative(meta.outputTokens())
                || isNegative(meta.costUsd())
                || isNegative(meta.credits())) {
            throw schemaViolation("Agent 근거 검증 meta가 올바르지 않습니다.");
        }
        if (!evidenceProviderInvoked(meta)
                && (!meta.model().startsWith("evidence-rules-")
                || meta.inputTokens() != 0L
                || meta.outputTokens() != 0L
                || meta.costUsd().compareTo(BigDecimal.ZERO) != 0
                || meta.credits().compareTo(BigDecimal.ZERO) != 0
                || meta.mock()
                || meta.truncated())) {
            throw schemaViolation("Agent rule-only 근거 검증 meta가 올바르지 않습니다.");
        }
    }

    private boolean evidenceProviderInvoked(AgentEvidenceResponse.Meta meta) {
        return !meta.promptVersion().startsWith("evidence.rules.");
    }

    private FindingAnalysisBullet unsupportedBullet(FindingAnalysisBullet bullet) {
        return new FindingAnalysisBullet(
                bullet.text(),
                List.of(),
                "ungrounded",
                BigDecimal.ZERO,
                StringUtils.hasText(bullet.groundingReason())
                        ? bullet.groundingReason()
                        : "근거 검증을 통과하지 못했습니다.",
                bullet.claimType(),
                bullet.attributedTo());
    }

    private List<FindingAnalysisSection> unsupportedSections(
            List<FindingAnalysisSection> sections) {
        return sections.stream()
                .map(section -> new FindingAnalysisSection(
                        section.heading(),
                        section.bullets().stream().map(this::unsupportedBullet).toList()))
                .toList();
    }

    private FindingAnalysisSection toAnalysisSection(AgentAnalyzeResponse.Section section,
                                                       int sentenceCount) {
        if (section == null || !StringUtils.hasText(section.heading())
                || section.bullets() == null || section.bullets().isEmpty()) {
            throw schemaViolation("Agent analysis section의 필수 필드가 없습니다.");
        }
        List<FindingAnalysisBullet> bullets = section.bullets().stream()
                .map(bullet -> toAnalysisBullet(bullet, sentenceCount))
                .toList();
        return new FindingAnalysisSection(section.heading().trim(), bullets);
    }

    private FindingAnalysisBullet toAnalysisBullet(AgentAnalyzeResponse.Bullet bullet,
                                                    int sentenceCount) {
        if (bullet == null || !StringUtils.hasText(bullet.text())
                || bullet.evidenceSentenceIds() == null || bullet.evidenceSentenceIds().isEmpty()
                || !GROUNDEDNESS_VALUES.contains(bullet.groundedness())
                || !CLAIM_TYPE_VALUES.contains(bullet.claimType())
                || ("OPINION".equals(bullet.claimType())
                != StringUtils.hasText(bullet.attributedTo()))
                || bullet.confidence() == null
                || bullet.confidence().compareTo(BigDecimal.ZERO) < 0
                || bullet.confidence().compareTo(BigDecimal.ONE) > 0) {
            throw schemaViolation("Agent analysis bullet의 필수 필드가 올바르지 않습니다.");
        }
        return new FindingAnalysisBullet(
                bullet.text().trim(),
                toPublicEvidenceIndexes(bullet.evidenceSentenceIds(), sentenceCount),
                bullet.groundedness(),
                bullet.confidence(),
                null,
                bullet.claimType(),
                "OPINION".equals(bullet.claimType()) ? bullet.attributedTo().trim() : null);
    }

    private FindingEntities toEntities(AgentAnalyzeResponse.Entities entities) {
        if (entities == null) {
            throw schemaViolation("Agent entities가 없습니다.");
        }
        return new FindingEntities(
                validatedEntityValues(entities.companies()),
                validatedEntityValues(entities.products()),
                validatedEntityValues(entities.technologies()));
    }

    private List<FindingPerspectiveTag> toPerspectiveTags(
            List<AgentAnalyzeResponse.PerspectiveTag> tags,
            int sentenceCount) {
        if (tags == null || tags.size() != AUDIENCE_COUNT) {
            throw schemaViolation("Agent perspectiveTags는 4개 관점을 모두 포함해야 합니다.");
        }
        Set<Audience> audiences = new HashSet<>();
        int highCount = 0;
        List<FindingPerspectiveTag> mapped = new ArrayList<>();
        for (AgentAnalyzeResponse.PerspectiveTag tag : tags) {
            if (tag == null) {
                throw schemaViolation("Agent perspectiveTag가 없습니다.");
            }
            Audience audience;
            AudienceRelevance relevance;
            try {
                audience = Audience.fromApiValue(tag.audience());
                relevance = AudienceRelevance.fromApiValue(tag.relevance());
            } catch (IllegalArgumentException exception) {
                throw schemaViolation("Agent perspectiveTag 값이 올바르지 않습니다.");
            }
            if (!audiences.add(audience)) {
                throw schemaViolation("Agent perspectiveTag audience가 중복되었습니다.");
            }
            List<Integer> agentEvidence = listOrEmpty(tag.evidenceSentenceIds());
            String hook = StringUtils.hasText(tag.hook()) ? tag.hook().trim() : null;
            if (relevance == AudienceRelevance.NONE) {
                if (hook != null || !agentEvidence.isEmpty()) {
                    throw schemaViolation("none 관점에는 hook과 evidence가 없어야 합니다.");
                }
            } else if (hook == null || agentEvidence.isEmpty()) {
                throw schemaViolation("관련 관점에는 hook과 evidence가 필요합니다.");
            }
            if (agentEvidence.size() != new HashSet<>(agentEvidence).size()) {
                throw schemaViolation("관점 evidence sentence id가 중복되었습니다.");
            }
            if (relevance == AudienceRelevance.HIGH) {
                highCount++;
            }
            mapped.add(new FindingPerspectiveTag(
                    audience,
                    relevance,
                    hook,
                    toPublicEvidenceIndexes(agentEvidence, sentenceCount)));
        }
        if (highCount > 2) {
            log.warn("Agent perspectiveTags의 high가 2개를 초과해 관점 태그만 제외한다.");
            return List.of();
        }
        return List.copyOf(mapped);
    }

    private List<String> validatedEntityValues(List<String> values) {
        if (values == null || values.stream().anyMatch(value -> !StringUtils.hasText(value))) {
            throw schemaViolation("Agent entity 배열이 올바르지 않습니다.");
        }
        return values.stream().map(String::trim).toList();
    }

    private AnalysisMetadata toMetadata(AgentAnalyzeResponse.Meta meta) {
        if (!StringUtils.hasText(meta.provider())
                || !StringUtils.hasText(meta.model())
                || !StringUtils.hasText(meta.promptVersion())
                || isNegative(meta.inputTokens())
                || isNegative(meta.outputTokens())
                || isNegative(meta.costUsd())
                || isNegative(meta.credits())) {
            throw schemaViolation("Agent meta가 올바르지 않습니다.");
        }
        return new AnalysisMetadata(
                meta.promptVersion(), meta.provider(), meta.model(),
                meta.inputTokens(), meta.outputTokens(), meta.costUsd(), meta.credits(),
                meta.truncated());
    }

    private boolean isNegative(Long value) {
        return value == null || value < 0;
    }

    private boolean isNegative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0;
    }

    private void validateCategory(String category) {
        if (!FindingCategory.ALLOWED_VALUES.contains(category)) {
            throw schemaViolation("지원하지 않는 finding category입니다.");
        }
    }

    private AgentClientException schemaViolation(String message) {
        return new AgentClientException("SCHEMA_VIOLATION", message);
    }

    private void recordSuccessSafely(Long runId,
                                     Long articleId,
                                     AgentAnalyzeRequest request,
                                     AgentAnalyzeResponse response,
                                     LocalDateTime startedAt) {
        try {
            recorder.recordSuccess(runId, articleId, request, response, startedAt);
        } catch (RuntimeException exception) {
            log.error("성공한 Agent 분석의 감사 로그를 기록하지 못했다. runId={} articleId={}",
                    runId, articleId, exception);
        }
    }

    private void recordFailureSafely(Long runId,
                                     Long articleId,
                                     AgentAnalyzeRequest request,
                                     String code,
                                     String message,
                                     AgentClientException.Usage usage,
                                     AgentTimeoutPhase timeoutPhase,
                                     LocalDateTime startedAt) {
        try {
            recorder.recordFailure(
                    runId, articleId, request, code, message, usage, timeoutPhase, startedAt);
        } catch (RuntimeException exception) {
            log.error("실패한 Agent 분석의 감사 로그를 기록하지 못했다. runId={} articleId={} code={}",
                    runId, articleId, code, exception);
        }
    }

    private void recordEvidenceSuccessSafely(Long runId,
                                             Long articleId,
                                             AgentEvidenceRequest request,
                                             AgentEvidenceResponse response,
                                             LocalDateTime startedAt) {
        try {
            recorder.recordEvidenceSuccess(runId, articleId, request, response, startedAt);
        } catch (RuntimeException exception) {
            log.error("성공한 Agent 근거 검증의 감사 로그를 기록하지 못했다. "
                            + "runId={} articleId={}",
                    runId, articleId, exception);
        }
    }

    private void recordEvidenceFailureSafely(Long runId,
                                             Long articleId,
                                             AgentEvidenceRequest request,
                                             String code,
                                             String message,
                                             AgentClientException.Usage usage,
                                             AgentTimeoutPhase timeoutPhase,
                                             LocalDateTime startedAt) {
        try {
            recorder.recordEvidenceFailure(
                    runId, articleId, request, code, message, usage, timeoutPhase, startedAt);
        } catch (RuntimeException exception) {
            log.error("실패한 Agent 근거 검증의 감사 로그를 기록하지 못했다. "
                            + "runId={} articleId={} code={}",
                    runId, articleId, code, exception);
        }
    }

    private void recordSelfCritiqueSuccessSafely(
            Long runId,
            Long issueId,
            AgentAnalyzeRequest request,
            AgentSelfCritiqueResponse response,
            LocalDateTime startedAt) {
        try {
            recorder.recordSelfCritiqueSuccess(runId, issueId, request, response, startedAt);
        } catch (RuntimeException exception) {
            log.error("성공한 Agent 자기 검증 감사 로그를 기록하지 못했다. "
                            + "runId={} issueId={}",
                    runId, issueId, exception);
        }
    }

    private void recordSelfCritiqueFailureSafely(
            Long runId,
            Long issueId,
            AgentAnalyzeRequest request,
            String code,
            String message,
            AgentClientException.Usage usage,
            AgentTimeoutPhase timeoutPhase,
            LocalDateTime startedAt) {
        try {
            recorder.recordSelfCritiqueFailure(
                    runId, issueId, request, code, message, usage, timeoutPhase, startedAt);
        } catch (RuntimeException exception) {
            log.error("실패한 Agent 자기 검증 감사 로그를 기록하지 못했다. "
                            + "runId={} issueId={} code={}",
                    runId, issueId, code, exception);
        }
    }

    private List<Integer> toPublicEvidenceIndexes(List<Integer> agentIds, int sentenceCount) {
        // Agent 내부 계약은 1부터 시작하지만 Notion 기사 API의 sentence index는 0부터 시작한다.
        return listOrEmpty(agentIds).stream().map(id -> {
            if (id == null || id < 1 || id > sentenceCount) {
                throw new AgentClientException("EVIDENCE_MISSING", "존재하지 않는 evidence sentence id입니다.");
            }
            return id - 1;
        }).toList();
    }

    private String analysisText(Article article) {
        if (StringUtils.hasText(article.getBody())) {
            return article.getBody();
        }
        if (StringUtils.hasText(article.getSummary())) {
            return article.getSummary();
        }
        return article.getTitle();
    }

    private <T> List<T> listOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record ReservationSelection(AgentPlan plan, QuotaReservation reservation) {
    }

    private record EvidenceClaimTarget(String claimId, FindingAnalysisBullet bullet) {
    }
}
