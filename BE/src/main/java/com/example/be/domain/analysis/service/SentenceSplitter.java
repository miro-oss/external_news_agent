package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.FindingSection;
import org.springframework.util.StringUtils;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SentenceSplitter {

    private SentenceSplitter() {
    }

    public static List<FindingSection> split(String text, String language) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        String normalized = text.replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        BreakIterator iterator = BreakIterator.getSentenceInstance(localeOf(language));
        iterator.setText(normalized);

        List<FindingSection> sections = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = normalized.substring(start, end).replaceAll("\\s+", " ").trim();
            if (!sentence.isEmpty()) {
                sections.add(new FindingSection(sections.size(), sentence));
            }
        }

        if (sections.isEmpty()) {
            sections.add(new FindingSection(0, normalized));
        }
        return List.copyOf(sections);
    }

    private static Locale localeOf(String language) {
        return language == null ? Locale.KOREAN : Locale.forLanguageTag(language);
    }
}
