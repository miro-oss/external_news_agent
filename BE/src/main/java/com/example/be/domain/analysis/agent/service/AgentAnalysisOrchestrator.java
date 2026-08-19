package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingAnalysisBullet;
import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import com.example.be.domain.analysis.entity.FindingCategory;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisMetadata;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAnalysisOrchestrator implements ArticleAnalysisOrchestrator {

    private static final Set<String> GROUNDEDNESS_VALUES =
            Set.of("grounded", "weak", "ungrounded");

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

        List<FindingAnalysisSection> analysisSections = response.sections().stream()
                .map(section -> toAnalysisSection(section, sentences.size()))
                .toList();
        if (analysisSections.isEmpty()) {
            throw schemaViolation("Agent 분석 응답에 analysis section이 없습니다.");
        }
        List<FindingKeyPoint> keyPoints = analysisSections.stream()
                .flatMap(section -> section.bullets().stream())
                .map(bullet -> new FindingKeyPoint(
                        bullet.text(), bullet.evidence(), bullet.groundedness()))
                .toList();
        AgentAnalyzeResponse.Classification classification = response.classification();
        if (!StringUtils.hasText(classification.intent())) {
            throw schemaViolation("Agent classification intent가 없습니다.");
        }
        validateCategory(classification.category());
        FindingEntities entities = toEntities(response.entities());
        AnalysisMetadata metadata = toMetadata(response.meta());
        return new AnalysisResult(
                response.summaryKo().trim(),
                keyPoints,
                classification.intent().trim(),
                Sentiment.fromApiValue(classification.sentiment()),
                RiskLevel.fromApiValue(classification.riskLevel()),
                Relevance.fromApiValue(classification.relevance()),
                classification.category(),
                sentences,
                response.meta().mock() ? AnalysisSource.STUB : AnalysisSource.LLM,
                analysisSections,
                entities,
                metadata);
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
                || bullet.confidence() == null
                || bullet.confidence().compareTo(BigDecimal.ZERO) < 0
                || bullet.confidence().compareTo(BigDecimal.ONE) > 0) {
            throw schemaViolation("Agent analysis bullet의 필수 필드가 올바르지 않습니다.");
        }
        return new FindingAnalysisBullet(
                bullet.text().trim(),
                toPublicEvidenceIndexes(bullet.evidenceSentenceIds(), sentenceCount),
                bullet.groundedness(),
                bullet.confidence());
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
