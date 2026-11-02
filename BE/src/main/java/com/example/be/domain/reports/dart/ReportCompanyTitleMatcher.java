package com.example.be.domain.reports.dart;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Matches saved identities only. A group, subsidiary, abbreviation or translated name is never inferred. */
final class ReportCompanyTitleMatcher {
    private static final String BOUNDARY = "[\\p{L}\\p{N}]";
    private static final String PARTICLES = "에서는|에서|으로|에게|보다|처럼|까지|부터|만의|와의|과의|의|은|는|이|가|을|를|와|과|도|만|에|로";

    private ReportCompanyTitleMatcher() { }

    static boolean name(String title, String name, List<Match> savedNames) {
        return matches(title, name).stream().anyMatch(match -> savedNames.stream()
                .noneMatch(longer -> longer.contains(match)));
    }

    static List<Match> matches(String title, String name) {
        if (title == null || name == null || name.isBlank() || name.length() > 512) return List.of();
        String identity = normalized(name).replaceAll("(?U)\\s+", "");
        if (identity.isEmpty()) return List.of();
        String spelling = identity.codePoints().mapToObj(value -> Pattern.quote(new String(Character.toChars(value))))
                .collect(Collectors.joining("\\s*"));
        return Pattern.compile("(?<!" + BOUNDARY + ")" + spelling + "(?=$|[^\\p{L}\\p{N}]|(?:"
                + PARTICLES + ")(?=$|[^\\p{L}\\p{N}]))", Pattern.UNICODE_CHARACTER_CLASS)
                .matcher(normalized(title)).results().map(match -> new Match(match.start(), match.end())).toList();
    }

    record Match(int start, int end) {
        boolean contains(Match other) {
            return start <= other.start && end >= other.end && (start < other.start || end > other.end);
        }
    }

    static boolean stockCode(String title, String code) {
        return title != null && code != null && code.matches("[0-9A-Z]{6}")
                && Pattern.compile("(?<!" + BOUNDARY + ")" + Pattern.quote(code) + "(?!" + BOUNDARY + ")")
                    .matcher(normalized(title)).find();
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
    }
}
