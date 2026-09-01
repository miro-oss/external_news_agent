package com.example.be.domain.collection.scoring;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.converter.TopicKeywordFilter;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/** AND/NOT 게이트는 유지하고 선택 키워드만 언어별 IDF 비율로 정규화한다. */
@Component
@RequiredArgsConstructor
public class TopicFitScorer {

    private final KeywordIdfWeights idfWeights;

    public TopicKeywordFilter.MatchResult evaluate(Topic topic,
                                                   Source source,
                                                   CollectedArticle article) {
        String language = languageOf(article.language(), source == null ? null : source.getLanguage());
        Map<String, Double> weights = weights(topic, language);
        return TopicKeywordFilter.evaluate(topic, article, weights);
    }

    public double score(Topic topic,
                        String title,
                        String summary,
                        String articleLanguage,
                        String sourceLanguage) {
        if (topic == null) {
            return 1.0d;
        }
        String language = languageOf(articleLanguage, sourceLanguage);
        return TopicKeywordFilter.topicFit(topic, title, summary, weights(topic, language));
    }

    private Map<String, Double> weights(Topic topic, String language) {
        List<String> optionalKeywords = TopicKeywordFilter.normalizedOptionalKeywords(topic);
        if (optionalKeywords.isEmpty()) {
            return Map.of();
        }
        return idfWeights.weights(language, optionalKeywords);
    }

    private String languageOf(String articleLanguage, String sourceLanguage) {
        return StringUtils.hasText(articleLanguage) ? articleLanguage : sourceLanguage;
    }
}
