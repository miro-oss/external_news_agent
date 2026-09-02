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

        List<Finding> findings = issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(issueId)
                .stream()
                .map(IssueArticle::getArticle)
                .map(article -> findingRepository.findFirstByArticleIdOrderByIdDesc(article.getId())
                        .orElse(null))
                .filter(finding -> finding != null
                        && AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .filter(finding -> !sentences(finding).isEmpty())
                .limit(MAX_FINDINGS)
                .toList();
        if (findings.isEmpty()) {
            throw new GeneralException(
                    GeneralErrorCode.CONFLICT,
                    "인사이트를 생성할 Agent 분석 finding이 없습니다.");
        }

        List<AgentInsightRequest.FindingPayload> findingPayloads = findings.stream()
                .map(this::toPayload)
                .toList();
        Long runId = findings.stream()
                .map(finding -> finding.getRun().getId())
                .max(Comparator.naturalOrder())
                .orElse(null);
        String inputHash = hash(new Fingerprint(topicPayload, findingPayloads));
        return new Snapshot(issueId, runId, inputHash, topicPayload, findingPayloads);
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

    public record Snapshot(Long issueId,
                           Long runId,
                           String inputHash,
                           AgentInsightRequest.TopicPayload topic,
                           List<AgentInsightRequest.FindingPayload> findings) {
    }
}
