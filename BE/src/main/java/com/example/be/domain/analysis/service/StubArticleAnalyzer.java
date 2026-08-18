package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 외부 모델 없이 같은 입력에 항상 같은 결과를 내는 M4 Stub.
 *
 * <p>영문을 완전 번역하는 척하지 않는다. 분류 키워드에 맞는 짧은 한국어 요약을 만들고, 실제 모델은
 * {@link ArticleAnalyzer} 경계 뒤에 붙인다. 로컬 PoC와 테스트에서 재현 가능하다는 게 Stub의 우선 계약이다.
 */
@Component
public class StubArticleAnalyzer implements ArticleAnalyzer {

    private static final int MAX_KEY_POINTS = 3;

    @Override
    public AnalysisResult analyze(Article article) {
        String fullText = article.getFetchStatus() == FetchStatus.FULLTEXT ? article.getBody() : null;
        String material = firstText(fullText, article.getSummary(), article.getTitle());
        List<FindingSection> sections = SentenceSplitter.split(material, article.getLanguage());
        String searchable = (article.getTitle() + " " + material).toLowerCase(Locale.ROOT);
        boolean hasFullText = StringUtils.hasText(fullText);

        return new AnalysisResult(
                summary(article, sections, searchable),
                keyPoints(sections, hasFullText),
                intent(searchable),
                sentiment(searchable),
                riskLevel(searchable),
                relevance(searchable),
                category(searchable),
                sections);
    }

    private String summary(Article article, List<FindingSection> sections, String searchable) {
        if (isEnglish(article.getLanguage())) {
            if (containsAny(searchable, "export control", "export restriction", "sanction")) {
                return "미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.";
            }
            if (containsAny(searchable, "hbm", "high bandwidth memory")) {
                return "해외에서 HBM 생산·출시 일정과 관련된 소식이 보도됐다.";
            }
            if (containsAny(searchable, "semiconductor", "chip", "foundry")) {
                return "해외 반도체 산업의 주요 동향이 보도됐다.";
            }
            return "해외 산업 동향과 관련된 새 기사가 수집됐다.";
        }

        return sections.isEmpty() ? article.getTitle() : sections.get(0).text();
    }

    private List<FindingKeyPoint> keyPoints(List<FindingSection> sections, boolean fullText) {
        String groundedness = fullText ? "grounded" : "weak";
        return sections.stream()
                .limit(MAX_KEY_POINTS)
                .map(section -> new FindingKeyPoint(
                        section.text(), List.of(section.index()), groundedness))
                .toList();
    }

    private String intent(String text) {
        if (containsAny(text, "규제", "정책", "통제", "regulation", "policy", "control")) {
            return "정책 변화 보도";
        }
        if (containsAny(text, "발표", "출시", "양산", "announce", "launch", "production")) {
            return "계획·제품 발표";
        }
        return "산업 동향 보도";
    }

    private Sentiment sentiment(String text) {
        if (containsAny(text, "강화", "금지", "감소", "하락", "지연", "위험",
                "tighten", "ban", "decline", "delay", "risk")) {
            return Sentiment.NEGATIVE;
        }
        if (containsAny(text, "성장", "증가", "확대", "앞당", "출시", "양산",
                "growth", "increase", "expand", "accelerat", "launch")) {
            return Sentiment.POSITIVE;
        }
        return Sentiment.NEUTRAL;
    }

    private RiskLevel riskLevel(String text) {
        if (containsAny(text, "수출 통제", "금지", "제재", "공급 중단",
                "export control", "ban", "sanction", "supply disruption")) {
            return RiskLevel.HIGH;
        }
        if (containsAny(text, "규제", "정책", "공급망", "가격", "regulation", "policy", "supply", "price")) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private Relevance relevance(String text) {
        if (containsAny(text, "반도체", "hbm", "semiconductor", "chip", "foundry", "wafer")) {
            return Relevance.IMPORTANT;
        }
        if (containsAny(text, "산업", "기술", "시장", "공급", "industry", "technology", "market", "supply")) {
            return Relevance.WATCH;
        }
        return Relevance.REFERENCE;
    }

    private String category(String text) {
        if (containsAny(text, "규제", "정책", "통제", "제재", "regulation", "policy", "control", "sanction")) {
            return "정책";
        }
        if (containsAny(text, "공급망", "수급", "물류", "supply chain", "shortage")) {
            return "공급망";
        }
        if (containsAny(text, "기업", "인수", "실적", "매출", "company", "acquisition", "revenue")) {
            return "기업";
        }
        return "제품/공정";
    }

    private boolean isEnglish(String language) {
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
    }

    private String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
