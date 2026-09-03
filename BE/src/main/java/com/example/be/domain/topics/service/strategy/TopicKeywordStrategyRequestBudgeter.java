package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TopicKeywordStrategyRequestBudgeter {

    static final int MAX_REQUEST_CHARS = 18_000;

    private final ObjectMapper objectMapper;

    public AgentKeywordStrategyRequest fit(AgentKeywordStrategyRequest request) {
        AgentKeywordStrategyRequest base = withArticles(request, List.of());
        if (!fits(base)) {
            throw new IllegalStateException("키워드 제안 기본 입력이 요청 크기 상한을 초과했습니다.");
        }

        List<AgentKeywordStrategyRequest.ArticleObservation> selected = new ArrayList<>();
        for (AgentKeywordStrategyRequest.ArticleObservation candidate : request.articles()) {
            List<AgentKeywordStrategyRequest.ArticleObservation> withCandidate = append(selected, candidate);
            if (fits(withArticles(request, withCandidate))) {
                selected.add(candidate);
                continue;
            }

            AgentKeywordStrategyRequest.ArticleObservation trimmed = fitSummary(request, selected, candidate);
            if (trimmed != null) {
                selected.add(trimmed);
            }
        }
        return withArticles(request, selected);
    }

    private AgentKeywordStrategyRequest.ArticleObservation fitSummary(
            AgentKeywordStrategyRequest request,
            List<AgentKeywordStrategyRequest.ArticleObservation> selected,
            AgentKeywordStrategyRequest.ArticleObservation candidate) {
        AgentKeywordStrategyRequest.ArticleObservation withoutSummary = withSummary(candidate, null);
        if (!fits(withArticles(request, append(selected, withoutSummary)))) {
            return null;
        }
        if (!StringUtils.hasText(candidate.summary())) {
            return withoutSummary;
        }

        int low = 0;
        int high = candidate.summary().length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            AgentKeywordStrategyRequest.ArticleObservation current =
                    withSummary(candidate, candidate.summary().substring(0, middle));
            if (fits(withArticles(request, append(selected, current)))) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low == 0
                ? withoutSummary
                : withSummary(candidate, candidate.summary().substring(0, low));
    }

    private boolean fits(AgentKeywordStrategyRequest request) {
        return objectMapper.writeValueAsString(request).length() <= MAX_REQUEST_CHARS;
    }

    private List<AgentKeywordStrategyRequest.ArticleObservation> append(
            List<AgentKeywordStrategyRequest.ArticleObservation> selected,
            AgentKeywordStrategyRequest.ArticleObservation candidate) {
        List<AgentKeywordStrategyRequest.ArticleObservation> result = new ArrayList<>(selected);
        result.add(candidate);
        return result;
    }

    private AgentKeywordStrategyRequest withArticles(
            AgentKeywordStrategyRequest request,
            List<AgentKeywordStrategyRequest.ArticleObservation> articles) {
        return new AgentKeywordStrategyRequest(
                request.idempotencyKey(),
                request.plan(),
                request.target(),
                request.topic(),
                request.run(),
                request.currentKeywordStats(),
                articles);
    }

    private AgentKeywordStrategyRequest.ArticleObservation withSummary(
            AgentKeywordStrategyRequest.ArticleObservation article,
            String summary) {
        return new AgentKeywordStrategyRequest.ArticleObservation(
                article.articleId(),
                article.title(),
                summary,
                article.publisher(),
                article.changeType(),
                article.publishedAt(),
                article.topicFit());
    }
}
