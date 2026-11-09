package com.example.be.domain.topics.entity;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Recognizes an explicitly configured technology topic, never an article's truth or quality. */
public final class TopicEditorialFocus {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern INDUSTRY_SCOPE = Pattern.compile(
            "산업|시장|투자|주가|실적|규제|정책|인프라|데이터\\s*센터|공급망"
                    + "|\\b(?:industry|market|investment|stocks?|earnings|regulation|policy|infrastructure)\\b"
                    + "|\\bdata\\s*cent(?:er|re)s?\\b|\\bsupply\\s+chain\\b", FLAGS);
    private static final Pattern PUBLIC_POLICY = Pattern.compile(
            "(?:공공|정부|규제|산업|경제|국가|개인정보)\\s*정책"
                    + "|\\b(?:public|government|regulatory|industrial|economic|national|privacy)\\s+polic(?:y|ies)\\b", FLAGS);
    private static final Pattern TECHNICAL_POLICY = Pattern.compile(
            "정책\\s*(?:최적화|경사|학습)"
                    + "|\\bpolicy[ -]+(?:optim(?:ization|isation)|gradients?|learning)\\b", FLAGS);
    private static final Pattern AI_CONTEXT = Pattern.compile(
            "인공\\s*지능|\\b(?:ai|llms?)\\b|언어\\s*모델|\\blarge\\s+language\\s+models?\\b", FLAGS);
    private static final Pattern TECHNICAL_SCOPE = Pattern.compile(
            "기술|모델|에이전트|알고리즘|학습|추론|검색|온톨로지|지식\\s*그래프|컨텍스트"
                    + "|\\b(?:technolog(?:y|ies)|technical|models?|agents?|agentic|algorithms?|training|inference|reasoning|retrieval|search|ontology|context)\\b",
            FLAGS);
    private static final Pattern SPECIFIC_METHOD = Pattern.compile(
            "\\b(?:llms?|graphrag|rag)\\b|검색\\s*증강\\s*생성|온톨로지|지식\\s*그래프"
                    + "|\\b(?:ontology|knowledge\\s+graphs?|retrieval[ -]augmented\\s+generation)\\b", FLAGS);

    private TopicEditorialFocus() { }

    public static boolean isAiTechnology(Topic topic) {
        if (topic == null) return false;
        return isAiTechnology(topic.getName(), topic.getQueryText());
    }

    public static boolean isAiTechnology(String name, String queryText) {
        // The relevance gate and search connector use queryText as the primary scope as well.
        String value = queryText;
        if (value == null || value.isBlank()) value = name;
        if (value == null || value.isBlank()) return false;
        String scope = Normalizer.normalize(value, Normalizer.Form.NFKC);
        // Learning-method policy terms do not imply public policy, but an explicit industry scope still wins.
        if (PUBLIC_POLICY.matcher(scope).find()
                || INDUSTRY_SCOPE.matcher(TECHNICAL_POLICY.matcher(scope).replaceAll("")).find()) return false;
        return SPECIFIC_METHOD.matcher(scope).find()
                || (AI_CONTEXT.matcher(scope).find() && TECHNICAL_SCOPE.matcher(scope).find());
    }
}
