package com.example.be.domain.reports.keyword;

import java.text.Normalizer;
import java.util.Locale;

/** Literal matching only: Latin letter/digit boundaries, with Korean substring matching. */
public final class ReportKeywordMatcher {
    private ReportKeywordMatcher() { }

    public static String label(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").strip();
    }

    public static String key(String value) { return label(value).toLowerCase(Locale.ROOT); }

    public static boolean matches(String normalizedKeyword, String title, String body) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) return false;
        return inText(normalizedKeyword, key(title)) || inText(normalizedKeyword, key(body));
    }

    private static boolean inText(String keyword, String text) {
        boolean leftBound = latinOrDigit(keyword.codePointAt(0));
        boolean rightBound = latinOrDigit(keyword.codePointBefore(keyword.length()));
        for (int from = 0; from <= text.length() - keyword.length();) {
            int start = text.indexOf(keyword, from);
            if (start < 0) return false;
            int end = start + keyword.length();
            boolean left = !leftBound || start == 0 || !latinOrDigit(text.codePointBefore(start));
            boolean right = !rightBound || end == text.length() || !latinOrDigit(text.codePointAt(end));
            if (left && right) return true;
            from = start + Character.charCount(text.codePointAt(start));
        }
        return false;
    }

    private static boolean latinOrDigit(int codePoint) {
        return Character.isDigit(codePoint) || Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }
}
