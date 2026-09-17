package com.example.be.domain.analysis.relevance;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/** Existing report fixtures predate relevance assessments and remain eligible unless a test overrides the verdict. */
public final class TopicRelevanceTestSupport {
    private TopicRelevanceTestSupport() { }

    public static TopicRelevancePolicy legacyPolicy() {
        return mock(TopicRelevancePolicy.class, invocation -> switch (invocation.getMethod().getName()) {
            case "filterFindings" -> invocation.getArgument(0);
            case "filterDailyFindings" -> invocation.getArgument(1);
            default -> RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
