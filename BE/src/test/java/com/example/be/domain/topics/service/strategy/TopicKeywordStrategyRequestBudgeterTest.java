package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicKeywordStrategyRequestBudgeterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TopicKeywordStrategyRequestBudgeter budgeter =
            new TopicKeywordStrategyRequestBudgeter(objectMapper);

    @Test
    void trimsArticlePayloadToStayBelowAgentInputLimit() {
        List<AgentKeywordStrategyRequest.ArticleObservation> articles = IntStream.range(0, 20)
                .mapToObj(index -> new AgentKeywordStrategyRequest.ArticleObservation(
                        (long) index + 1,
                        "긴 제목 ".repeat(120),
                        "긴 요약 ".repeat(500),
                        "테스트 매체",
                        "NEW",
                        null,
                        new BigDecimal("0.5000")))
                .toList();

        AgentKeywordStrategyRequest fitted = budgeter.fit(request(List.of("HBM"), articles));

        assertThat(objectMapper.writeValueAsString(fitted).length())
                .isLessThanOrEqualTo(TopicKeywordStrategyRequestBudgeter.MAX_REQUEST_CHARS);
        assertThat(fitted.articles()).isNotEmpty();
        assertThat(fitted.articles().size()).isLessThan(articles.size());
    }

    @Test
    void rejectsOversizedBasePayloadBeforeArticlesAreAdded() {
        List<String> keywords = IntStream.range(0, 100)
                .mapToObj(index -> ("키워드" + index).repeat(15))
                .toList();

        assertThatThrownBy(() -> budgeter.fit(request(keywords, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기본 입력");
    }

    private AgentKeywordStrategyRequest request(
            List<String> requiredKeywords,
            List<AgentKeywordStrategyRequest.ArticleObservation> articles) {
        List<AgentKeywordStrategyRequest.KeywordStat> stats = requiredKeywords.stream()
                .map(keyword -> new AgentKeywordStrategyRequest.KeywordStat("REQUIRED", keyword, 1))
                .toList();
        return new AgentKeywordStrategyRequest(
                "run:42:topic:7:keyword-strategy",
                AgentPlan.FREE,
                new AgentKeywordStrategyRequest.Target("TOPIC", 7L),
                new AgentKeywordStrategyRequest.Topic(
                        "HBM", "HBM 반도체", requiredKeywords, List.of(), List.of()),
                new AgentKeywordStrategyRequest.Run(42L, "SCHEDULED", 20, 20, 0),
                stats,
                articles);
    }
}
