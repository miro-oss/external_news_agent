package com.example.be.domain.insights.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class InsightInputAssembler {

    private static final int MAX_FINDINGS = 10;

    private final NewsIssueRepository issueRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final FindingRepository findingRepository;
    private final ObjectMapper objectMapper;

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
                .map(finding -> new SelectedFinding(finding, toPayload(finding)))
                .filter(selected -> !selected.payload().sentences().isEmpty())
                .limit(MAX_FINDINGS)
                .toList();
        if (selectedFindings.isEmpty()) {
            throw new GeneralException(
                    GeneralErrorCode.CONFLICT,
                    "이 이슈는 아직 분석된 기사가 없어 인사이트를 만들 수 없습니다.");
        }

        List<AgentInsightRequest.FindingPayload> findingPayloads = selectedFindings.stream()
                .map(SelectedFinding::payload)
                .toList();
        Long runId = selectedFindings.stream()
                .map(selected -> selected.finding().getRun().getId())
                .max(Comparator.naturalOrder())
                .orElse(null);
        Map<Long, Long> articleIdsByFinding = selectedFindings.stream()
                .collect(Collectors.toUnmodifiableMap(
                        selected -> selected.finding().getId(),
                        selected -> selected.finding().getArticle().getId()));
        String inputHash = hash(new Fingerprint(topicPayload, findingPayloads));
        return new Snapshot(
                issueId, runId, inputHash, topicPayload, findingPayloads, articleIdsByFinding);
    }

    private AgentInsightRequest.FindingPayload toPayload(Finding finding) {
        return new AgentInsightRequest.FindingPayload(
                finding.getId(),
                finding.getArticle().getTitle(),
                finding.getArticle().getCanonicalUrl(),
                finding.getSummary(),
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
