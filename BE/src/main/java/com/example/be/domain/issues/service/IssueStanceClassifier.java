package com.example.be.domain.issues.service;

import com.example.be.domain.collection.cluster.DeterministicEntityExtractor;
import com.example.be.domain.collection.cluster.TitleTokenizer;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueStance;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 제목·요약의 직접 비교만으로 이슈에 대한 RULE stance 후보를 만든다. */
@Component
public class IssueStanceClassifier {

    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9])[-+]?\\d[\\d,]*(?:\\.\\d+)?(?![A-Za-z0-9])");
    private static final Pattern NEGATION = Pattern.compile(
            "(?:\\b(?:not|no|never|without|deny|denied|denies)\\b|않|아니|없|무산|취소|중단)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECTION = Pattern.compile(
            "(?:정정|오보|철회|사실무근|잘못(?:된|됐다|알려)|거짓|허위|"
                    + "\\b(?:correction|corrected|retract(?:ed|ion)?|false report|not true)\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.850");
    private static final BigDecimal ADDITION_CONFIDENCE = new BigDecimal("0.650");
    private static final BigDecimal LOW_CONFIDENCE = new BigDecimal("0.550");
    private static final BigDecimal REPRESENTATIVE_CONFIDENCE = new BigDecimal("0.900");
    private static final double SUPPORT_OVERLAP_THRESHOLD = 0.50d;

    private final DeterministicEntityExtractor entityExtractor = new DeterministicEntityExtractor();

    public Result classify(Article representative, Article candidate) {
        if (representative == null || candidate == null) {
            throw new IllegalArgumentException("stance를 분류할 기사와 대표 기사가 필요합니다.");
        }
        if (representative.getId() != null && representative.getId().equals(candidate.getId())) {
            return new Result(IssueStance.SUPPORTS, REPRESENTATIVE_CONFIDENCE);
        }

        String reference = comparisonText(representative);
        String current = comparisonText(candidate);
        if (hasExplicitCorrection(candidate)) {
            return new Result(IssueStance.RETRACTS, HIGH_CONFIDENCE);
        }

        Set<String> referenceNumbers = numbers(reference);
        Set<String> currentNumbers = numbers(current);
        boolean numberMismatch = !referenceNumbers.isEmpty()
                && !currentNumbers.isEmpty()
                && !referenceNumbers.equals(currentNumbers);
        boolean polarityMismatch = NEGATION.matcher(reference).find()
                != NEGATION.matcher(current).find();
        if (numberMismatch || polarityMismatch) {
            return new Result(IssueStance.DISPUTES, HIGH_CONFIDENCE);
        }

        Set<String> referenceEntities = entities(representative);
        Set<String> currentEntities = entities(candidate);
        boolean addsFact = currentNumbers.stream().anyMatch(value -> !referenceNumbers.contains(value))
                || currentEntities.stream().anyMatch(value -> !referenceEntities.contains(value));
        if (addsFact) {
            return new Result(IssueStance.ADDS, ADDITION_CONFIDENCE);
        }

        Set<String> referenceTokens = TitleTokenizer.tokens(reference);
        Set<String> currentTokens = TitleTokenizer.tokens(current);
        int common = (int) currentTokens.stream().filter(referenceTokens::contains).count();
        int denominator = Math.max(1, Math.min(referenceTokens.size(), currentTokens.size()));
        double overlap = (double) common / denominator;
        BigDecimal confidence = common >= 2 && overlap >= SUPPORT_OVERLAP_THRESHOLD
                ? HIGH_CONFIDENCE
                : LOW_CONFIDENCE;
        return new Result(IssueStance.SUPPORTS, confidence);
    }

    public boolean hasExplicitCorrection(Article article) {
        return article != null && CORRECTION.matcher(comparisonText(article)).find();
    }

    private Set<String> entities(Article article) {
        return entityExtractor.extract(
                article.getTitle(), article.getSummary(), null, java.util.List.of());
    }

    private Set<String> numbers(String value) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group().replace(",", ""));
        }
        return Set.copyOf(result);
    }

    private String comparisonText(Article article) {
        return normalize(String.join("\n", nullToEmpty(article.getTitle()), nullToEmpty(article.getSummary())));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record Result(IssueStance stance, BigDecimal confidence) {

        public Result {
            if (stance == null || confidence == null || confidence.signum() < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("stance 분류 결과가 올바르지 않습니다.");
            }
        }
    }
}
