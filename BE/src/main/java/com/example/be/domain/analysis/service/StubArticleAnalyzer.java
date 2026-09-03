package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.FindingCategory;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.FindingSensitivityAxis;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.content.ArticleBodyCleaner;
import com.example.be.domain.collection.entity.FetchStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 외부 모델 없이 같은 입력에 항상 같은 결과를 내는 M4 Stub.
 *
 * <p>영문을 완전 번역하는 척하지 않는다. 분류 키워드에 맞는 짧은 한국어 요약을 만들고, 실제 모델은
 * {@link ArticleAnalyzer} 경계 뒤에 붙인다. 로컬 PoC와 테스트에서 재현 가능하다는 게 Stub의 우선 계약이다.
 */
@Component
@RequiredArgsConstructor
public class StubArticleAnalyzer implements ArticleAnalyzer {

    private static final int MAX_KEY_POINTS = 3;
    private static final Pattern EMAIL_ONLY = Pattern.compile(
            "^[\\p{Alnum}._%+-]+@[\\p{Alnum}.-]+\\.[A-Za-z]{2,}$");
    private static final List<String> BOILERPLATE_MARKERS = List.of(
            "ai가 자동 생성한 요약",
            "자동 생성한 요약",
            "정확하지 않을 수",
            "이 기사는 회원 가입이 필요한 프리미엄 기사",
            "회원가입이 필요한 프리미엄 콘텐츠",
            "로그인 후 이용할 수 있습니다",
            "구독 후 이용할 수 있습니다",
            "무단 전재",
            "재배포 금지",
            "sign up to continue reading",
            "log in to continue reading",
            "subscribe to continue reading",
            "all rights reserved");

    private final SensitivityCalculator sensitivityCalculator;

    @Override
    public AnalysisResult analyze(Article article) {
        String fullText = article.getFetchStatus() == FetchStatus.FULLTEXT
                ? ArticleBodyCleaner.withoutTrailingBoilerplate(article.getBody())
                : null;
        String material = firstText(fullText, article.getSummary(), article.getTitle());
        List<FindingSection> sections = SentenceSplitter.split(material, article.getLanguage());
        List<FindingSection> meaningfulSections = sections.stream()
                .filter(section -> isMeaningful(section.text()))
                .toList();
        List<FindingSection> sensitivitySections = meaningfulSections;
        if (sensitivitySections.isEmpty() && StringUtils.hasText(article.getTitle())) {
            FindingSection titleSection = new FindingSection(sections.size(), article.getTitle().trim());
            List<FindingSection> sectionsWithTitle = new ArrayList<>(sections);
            sectionsWithTitle.add(titleSection);
            sections = List.copyOf(sectionsWithTitle);
            sensitivitySections = List.of(titleSection);
        }
        String meaningfulText = meaningfulSections.stream()
                .map(FindingSection::text)
                .collect(Collectors.joining(" "));
        String searchable = (article.getTitle() + " " + meaningfulText).toLowerCase(Locale.ROOT);
        boolean hasFullText = StringUtils.hasText(fullText);

        return new AnalysisResult(
                summary(article, meaningfulSections, searchable),
                keyPoints(meaningfulSections, hasFullText),
                intent(searchable),
                sentiment(searchable),
                sensitivity(sensitivitySections),
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

        if (!sections.isEmpty()) {
            return sections.get(0).text();
        }
        return isMeaningful(article.getTitle())
                ? article.getTitle()
                : "수집된 기사에서 요약할 수 있는 본문을 찾지 못했습니다.";
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

    private FindingSensitivity sensitivity(List<FindingSection> sections) {
        FindingSensitivityAxis customerMove = matchingAxis(sections,
                "증설", "양산", "도입", "투자", "감산", "expand", "production", "adopt", "invest");
        FindingSensitivityAxis dealSignal = matchingAxis(sections,
                "계약", "수주", "발주", "구매", "입찰", "예산", "contract", "order", "procure", "bid");
        FindingSensitivityAxis competitorThreat = matchingAxis(sections,
                "경쟁", "점유율", "신제품", "내재화", "대체", "competitor", "market share", "launch", "replace");
        FindingSensitivityAxis industryShift = matchingAxis(sections,
                "수출 통제", "금지", "제재", "공급 중단", "규제", "정책", "제한",
                "export control", "ban", "sanction", "supply disruption", "regulation",
                "policy", "restriction");
        if (customerMove.score() == null && dealSignal.score() == null
                && competitorThreat.score() == null && industryShift.score() == null) {
            FindingSection fallback = sections.getFirst();
            customerMove = new FindingSensitivityAxis(0, List.of(fallback.index()));
        }
        return sensitivityCalculator.calculate(
                customerMove,
                dealSignal,
                competitorThreat,
                industryShift);
    }

    private FindingSensitivityAxis matchingAxis(List<FindingSection> sections, String... keywords) {
        return sections.stream()
                .filter(section -> containsAny(section.text().toLowerCase(Locale.ROOT), keywords))
                .findFirst()
                .map(section -> new FindingSensitivityAxis(3, List.of(section.index())))
                .orElseGet(FindingSensitivityAxis::unavailable);
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
            return FindingCategory.POLICY;
        }
        if (containsAny(text, "공급망", "수급", "물류", "supply chain", "shortage")) {
            return FindingCategory.SUPPLY_CHAIN;
        }
        if (containsAny(text, "기업", "인수", "실적", "매출", "company", "acquisition", "revenue")) {
            return FindingCategory.COMPANY;
        }
        return FindingCategory.PRODUCT_PROCESS;
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

    private boolean isMeaningful(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.replaceAll("[^\\p{L}\\p{N}]", "");
        if (normalized.length() < 4 || EMAIL_ONLY.matcher(text.trim()).matches()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return BOILERPLATE_MARKERS.stream().noneMatch(lowered::contains);
    }
}
