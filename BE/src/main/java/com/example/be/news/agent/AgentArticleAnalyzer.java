package com.example.be.news.agent;

import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisResult;
import com.example.be.domain.analysis.service.ArticleAnalyzer;
import com.example.be.domain.analysis.service.StubArticleAnalyzer;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class AgentArticleAnalyzer implements ArticleAnalyzer {

    private final AgentProperties properties;
    private final AgentClient client;
    private final AgentRunRecorder recorder;
    private final StubArticleAnalyzer stubAnalyzer;

    @Override
    public AnalysisResult analyze(Long runId, Article article) {
        if (!properties.isEnabled()) {
            return stubAnalyzer.analyze(runId, article);
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
            recorder.recordFailure(runId, article.getId(), request, code, exception.getMessage(), startedAt);
            log.warn("Agent 분석에 실패해 Stub으로 대체한다. runId={} articleId={} code={} error={}",
                    runId, article.getId(), code, exception.getMessage());
            return stubAnalyzer.analyze(runId, article);
        }

        recorder.recordSuccess(runId, article.getId(), request, response, startedAt);
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
