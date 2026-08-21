package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.converter.ArticleHasher;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.topics.entity.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 같은 분석 입력과 LLM 계약으로 생성된 finding을 한 번의 조회로 찾아 재사용한다. */
@Component
@RequiredArgsConstructor
public class FindingReuseCache {

    private static final String FREE_PROVIDER = "gemini";
    private static final String PAID_PROVIDER = "mindlogic-claude";

    private final FindingRepository findingRepository;
    private final AgentProperties properties;

    @Transactional(readOnly = true)
    public Map<Long, Lookup> lookupAll(List<Article> articles, AgentPlan plan) {
        Map<Long, String> inputHashes = new LinkedHashMap<>();
        articles.forEach(article -> inputHashes.put(article.getId(), inputHash(article)));
        if (inputHashes.isEmpty()) {
            return Map.of();
        }

        Map<Long, AnalysisResult> cachedByArticleId = new LinkedHashMap<>();
        contract(plan).ifPresent(contract -> reusableSources(inputHashes, contract).forEach(finding -> {
            Long articleId = finding.getArticle().getId();
            if (Objects.equals(inputHashes.get(articleId), finding.getAnalysisInputHash())) {
                cachedByArticleId.putIfAbsent(articleId, toReusedResult(finding));
            }
        }));

        Map<Long, Lookup> lookups = new LinkedHashMap<>();
        inputHashes.forEach((articleId, inputHash) -> lookups.put(
                articleId,
                new Lookup(inputHash, Optional.ofNullable(cachedByArticleId.get(articleId)))));
        return Map.copyOf(lookups);
    }

    private List<Finding> reusableSources(Map<Long, String> inputHashes, CacheContract contract) {
        Collection<String> distinctHashes = new LinkedHashSet<>(inputHashes.values());
        return findingRepository.findReusableSources(
                inputHashes.keySet(),
                AnalysisSource.LLM,
                distinctHashes,
                contract.promptVersion(),
                contract.provider(),
                contract.model());
    }

    private Optional<CacheContract> contract(AgentPlan plan) {
        String promptVersion = properties.getAnalysisPromptVersion();
        String model = plan == AgentPlan.FREE ? properties.getFreeModel() : properties.getPaidModel();
        if (!StringUtils.hasText(promptVersion) || !StringUtils.hasText(model)) {
            return Optional.empty();
        }
        String provider = plan == AgentPlan.FREE ? FREE_PROVIDER : PAID_PROVIDER;
        return Optional.of(new CacheContract(promptVersion.trim(), provider, model.trim()));
    }

    private AnalysisResult toReusedResult(Finding finding) {
        AnalysisMetadata metadata = new AnalysisMetadata(
                finding.getPromptVersion(),
                finding.getLlmProvider(),
                finding.getLlmModel(),
                0L,
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                finding.isInputTruncated());
        return new AnalysisResult(
                finding.getSummary(),
                finding.getEffectiveKeyPoints(),
                finding.getIntent(),
                finding.getSentiment(),
                finding.getRiskLevel(),
                finding.getRelevance(),
                finding.getCategory(),
                finding.getSections(),
                AnalysisSource.REUSED,
                finding.getAnalysisSections(),
                finding.getEntities(),
                metadata);
    }

    static String inputHash(Article article) {
        List<String> fields = new ArrayList<>();
        fields.add(article.getTitle());
        fields.add(article.getSummary());
        fields.add(article.getBody());
        fields.add(article.getCanonicalUrl());
        fields.add(article.getLanguage());
        fields.add(article.getPublishedAt() == null ? null : article.getPublishedAt().toString());
        appendTopic(fields, article.getTopic());
        return ArticleHasher.analysisInputHash(fields.toArray(String[]::new));
    }

    private static void appendTopic(List<String> fields, Topic topic) {
        if (topic == null) {
            fields.add(null);
            return;
        }
        fields.add(topic.getName());
        fields.add(topic.getQueryText());
        appendValues(fields, topic.getRequiredKeywords());
        appendValues(fields, topic.getOptionalKeywords());
        appendValues(fields, topic.getExcludedKeywords());
    }

    private static void appendValues(List<String> fields, List<String> values) {
        List<String> safeValues = values == null ? List.of() : values;
        fields.add(Integer.toString(safeValues.size()));
        fields.addAll(safeValues);
    }

    public record Lookup(String analysisInputHash, Optional<AnalysisResult> cached) {

        public Lookup {
            Objects.requireNonNull(analysisInputHash, "analysisInputHash는 필수입니다.");
            cached = cached == null ? Optional.empty() : cached;
        }
    }

    private record CacheContract(String promptVersion, String provider, String model) {
    }
}
