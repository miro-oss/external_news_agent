package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.entity.FindingCategory;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisResult;
import com.example.be.domain.analysis.service.AnalysisContext;
import com.example.be.domain.analysis.service.ArticleAnalysisOrchestrator;
import com.example.be.domain.analysis.service.StubArticleAnalyzer;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAnalysisOrchestrator implements ArticleAnalysisOrchestrator {

    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentRunRecorder recorder;
    private final StubArticleAnalyzer stubAnalyzer;

    @Override
    public AnalysisResult analyze(AnalysisContext context) {
        Long runId = context.runId();
        Article article = context.article();
        if (!properties.isEnabled()) {
            return stubAnalyzer.analyze(article);
        }

        AgentAnalyzeRequest request = request(runId, article);
        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentAnalyzeResponse response;
        AnalysisResult result;
        try {
            response = client.analyze(request);
            result = toAnalysisResult(response);
        } catch (RuntimeException exception) {
            String code = exception instanceof AgentClientException clientException
                    ? clientException.getCode()
                    : "SCHEMA_VIOLATION";
            recordFailureSafely(runId, article.getId(), request, code, exception.getMessage(), startedAt);
            log.warn("Agent 분석에 실패해 Stub으로 대체한다. runId={} articleId={} code={} error={}",
                    runId, article.getId(), code, exception.getMessage());
            return stubAnalyzer.analyze(article);
        }

        recordSuccessSafely(runId, article.getId(), request, response, startedAt);
        return result;
    }

    private AgentAnalyzeRequest request(Long runId, Article article) {
        Topic topic = article.getTopic();
        return new AgentAnalyzeRequest(
                "run:" + runId + ":article:" + article.getId(),
                properties.getDefaultPlan(),
                new AgentAnalyzeRequest.ArticlePayload(
                        article.getId(),
                        article.getTitle(),
                        article.getCanonicalUrl(),
                        article.getLanguage(),
                        article.getPublishedAt(),
                        analysisText(article)),
                new AgentAnalyzeRequest.TopicPayload(
                        topic.getName(),
                        topic.getQueryText(),
                        listOrEmpty(topic.getRequiredKeywords()),
                        listOrEmpty(topic.getOptionalKeywords()),
                        listOrEmpty(topic.getExcludedKeywords())),
                null);
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

        List<FindingKeyPoint> keyPoints = response.sections().stream()
                .flatMap(section -> listOrEmpty(section.bullets()).stream())
                .map(bullet -> new FindingKeyPoint(
                        bullet.text(),
                        toPublicEvidenceIndexes(bullet.evidenceSentenceIds(), sentences.size()),
                        bullet.groundedness()))
                .toList();
        AgentAnalyzeResponse.Classification classification = response.classification();
        validateCategory(classification.category());
        return new AnalysisResult(
                response.summaryKo(),
                keyPoints,
                classification.intent(),
                Sentiment.fromApiValue(classification.sentiment()),
                RiskLevel.fromApiValue(classification.riskLevel()),
                Relevance.fromApiValue(classification.relevance()),
                classification.category(),
                sentences);
    }

    private void validateCategory(String category) {
        if (!FindingCategory.ALLOWED_VALUES.contains(category)) {
            throw new AgentClientException("SCHEMA_VIOLATION", "지원하지 않는 finding category입니다.");
        }
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
                                     LocalDateTime startedAt) {
        try {
            recorder.recordFailure(runId, articleId, request, code, message, startedAt);
        } catch (RuntimeException exception) {
            log.error("실패한 Agent 분석의 감사 로그를 기록하지 못했다. runId={} articleId={} code={}",
                    runId, articleId, code, exception);
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
}
