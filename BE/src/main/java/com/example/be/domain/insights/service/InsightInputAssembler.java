package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.exception.IssueException;
import com.example.be.domain.issues.exception.code.IssueErrorCode;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class InsightInputAssembler {

    private static final int MIN_HISTORY_CANDIDATE_PAGE_SIZE = 50;

    private final AgentProperties properties;
    private final NewsIssueRepository issueRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final FindingRepository findingRepository;
    private final ObjectMapper objectMapper;
    private final InsightEntityNormalizer entityNormalizer;

    @Transactional(readOnly = true)
    public Snapshot assemble(Long issueId) {
        NewsIssue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new IssueException(IssueErrorCode.ISSUE_NOT_FOUND));
        Topic topic = issue.getTopic();
        AgentInsightRequest.TopicPayload topicPayload = new AgentInsightRequest.TopicPayload(
                topic.getName(),
                topic.getQueryText(),
                listOrEmpty(topic.getRequiredKeywords()),
                listOrEmpty(topic.getOptionalKeywords()),
                listOrEmpty(topic.getExcludedKeywords()));

        List<IssueArticle> memberships =
                issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(issueId);
        List<Long> articleIds = memberships.stream()
                .map(membership -> membership.getArticle().getId())
                .toList();
        Map<Long, Finding> latestByArticleId = articleIds.isEmpty()
                ? Map.of()
                : findingRepository.findLatestByArticleIds(articleIds).stream()
                        .collect(Collectors.toMap(
                                finding -> finding.getArticle().getId(),
                                Function.identity()));
        List<SelectedFinding> selectedFindings = memberships.stream()
                .map(IssueArticle::getArticle)
                .map(article -> latestByArticleId.get(article.getId()))
                .filter(finding -> finding != null
                        && AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .map(finding -> new SelectedFinding(
                        finding,
                        toPayload(finding, AgentInsightRequest.FindingRole.CURRENT)))
                .filter(selected -> !selected.payload().sentences().isEmpty())
                .limit(AgentProperties.MAX_CURRENT_INSIGHT_FINDINGS)
                .toList();
        if (selectedFindings.isEmpty()) {
            throw new GeneralException(
                    GeneralErrorCode.CONFLICT,
                    "이 이슈는 아직 분석된 기사가 없어 인사이트를 만들 수 없습니다.");
        }
        List<SelectedFinding> historyFindings = historyFindings(topic.getId(), selectedFindings);
        List<SelectedFinding> allFindings = Stream.concat(
                        selectedFindings.stream(),
                        historyFindings.stream())
                .toList();

        List<AgentInsightRequest.FindingPayload> findingPayloads = allFindings.stream()
                .map(SelectedFinding::payload)
                .toList();
        Long runId = selectedFindings.stream()
                .map(selected -> selected.finding().getRun().getId())
                .max(Comparator.naturalOrder())
                .orElse(null);
        Map<Long, Long> articleIdsByFinding = allFindings.stream()
                .collect(Collectors.toUnmodifiableMap(
                        selected -> selected.finding().getId(),
                        selected -> selected.finding().getArticle().getId()));
        String inputHash = hash(new Fingerprint(topicPayload, findingPayloads));
        return new Snapshot(
                issueId, runId, inputHash, topicPayload, findingPayloads, articleIdsByFinding);
    }

    private List<SelectedFinding> historyFindings(Long topicId,
                                                  List<SelectedFinding> currentFindings) {
        Set<String> normalizedEntityNames = currentFindings.stream()
                .flatMap(selected -> normalizedEntityNames(selected.finding()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedEntityNames.isEmpty()) {
            return List.of();
        }
        Set<Long> currentFindingIds = currentFindings.stream()
                .map(selected -> selected.finding().getId())
                .collect(Collectors.toSet());
        Set<Long> currentArticleIds = currentFindings.stream()
                .map(selected -> selected.finding().getArticle().getId())
                .collect(Collectors.toSet());
        OffsetDateTime currentBaseline = currentFindings.stream()
                .map(selected -> effectivePublishedAt(selected.finding()))
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (currentBaseline == null) {
            return List.of();
        }
        OffsetDateTime since = OffsetDateTime.now(ApiTimeZone.ZONE)
                .toLocalDate()
                .minusDays(properties.getInsightHistory().getDays())
                .atStartOfDay(ApiTimeZone.ZONE)
                .toOffsetDateTime();
        int limit = properties.getInsightHistory().getLimit();
        int pageSize = Math.max(limit * 10, MIN_HISTORY_CANDIDATE_PAGE_SIZE);
        List<SelectedFinding> selected = new ArrayList<>(limit);

        for (int page = 0; selected.size() < limit; page++) {
            List<Finding> candidates = findingRepository.findInsightHistoryCandidates(
                    topicId,
                    since,
                    currentBaseline,
                    List.of(AnalysisSource.LLM, AnalysisSource.REUSED),
                    PageRequest.of(page, pageSize));
            candidates.stream()
                    .filter(finding -> !currentFindingIds.contains(finding.getId()))
                    .filter(finding -> !currentArticleIds.contains(finding.getArticle().getId()))
                    .filter(finding -> overlapsAnyEntity(finding, normalizedEntityNames))
                    .map(finding -> new SelectedFinding(
                            finding,
                            toPayload(finding, AgentInsightRequest.FindingRole.HISTORY)))
                    .filter(candidate -> !candidate.payload().sentences().isEmpty())
                    .limit(limit - selected.size())
                    .forEach(selected::add);
            if (candidates.size() < pageSize) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private AgentInsightRequest.FindingPayload toPayload(Finding finding,
                                                         AgentInsightRequest.FindingRole role) {
        return new AgentInsightRequest.FindingPayload(
                finding.getId(),
                finding.getArticle().getTitle(),
                finding.getArticle().getCanonicalUrl(),
                finding.getSummary(),
                role,
                publishedAt(finding),
                sentences(finding));
    }

    private List<AgentInsightRequest.SentencePayload> sentences(Finding finding) {
        if (finding.getSections() == null) {
            return List.of();
        }
        return finding.getSections().stream()
                .filter(section -> section != null
                        && section.index() >= 0
                        && StringUtils.hasText(section.text()))
                .map(section -> new AgentInsightRequest.SentencePayload(
                        section.index() + 1,
                        section.text()))
                .toList();
    }

    private Set<String> normalizedEntityNames(Finding finding) {
        return finding.getEntities() == null
                ? Set.of()
                : entityNormalizer.normalize(finding.getEntities().allNames());
    }

    private boolean overlapsAnyEntity(Finding finding, Set<String> normalizedEntityNames) {
        return normalizedEntityNames(finding).stream().anyMatch(normalizedEntityNames::contains);
    }

    private String publishedAt(Finding finding) {
        return finding.getArticle().getPublishedAt() == null
                ? null
                : finding.getArticle().getPublishedAt()
                        .atZoneSameInstant(ApiTimeZone.ZONE)
                        .toLocalDate()
                        .toString();
    }

    private OffsetDateTime effectivePublishedAt(Finding finding) {
        if (finding.getArticle().getPublishedAt() != null) {
            return finding.getArticle().getPublishedAt();
        }
        return finding.getAnalyzedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private String hash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(value));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private List<String> listOrEmpty(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record Fingerprint(AgentInsightRequest.TopicPayload topic,
                               List<AgentInsightRequest.FindingPayload> findings) {
    }

    private record SelectedFinding(Finding finding,
                                   AgentInsightRequest.FindingPayload payload) {
    }

    public record Snapshot(Long issueId,
                           Long runId,
                           String inputHash,
                           AgentInsightRequest.TopicPayload topic,
                           List<AgentInsightRequest.FindingPayload> findings,
                           Map<Long, Long> articleIdsByFinding) {
    }
}
