package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IssueClustererRepresentativeTest {
    private static final String TITLE = "새빛전자 메모리 설비 확장 발표";
    private static final String BODY = "새빛전자는 메모리 설비를 확장한다고 밝혔다.";
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2027-10-12T12:00:00+09:00");
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void fullTextBreakingArticleOutranksANonBreakingSummaryFromAStrongerSource() {
        var summary = article(1, TITLE, FetchStatus.METADATA_ONLY, null, "0.99", 0, null, true);
        var fullText = article(2, "[속보] " + TITLE, FetchStatus.FULLTEXT, BODY, "0.40", 1, null, true);

        assertRepresentative(2, null, List.of(2L), summary, fullText);
    }

    @Test
    void shortFullTextOutranksAnEarlierHigherReliabilitySummary() {
        var summary = article(1, TITLE, FetchStatus.METADATA_ONLY, null, "0.99", 0, null, true);
        var fullText = article(2, TITLE, FetchStatus.FULLTEXT, BODY, "0.40", 1, null, true);

        assertRepresentative(2, null, List.of(2L), summary, fullText);
    }

    @Test
    void fullTextStatusWithoutAnActualBodyDoesNotReceivePriority() {
        var fullText = article(2, TITLE, FetchStatus.FULLTEXT, BODY, "0.40", 1, null, true);
        for (String absent : new String[]{null, "", " \n\t "}) {
            var empty = article(1, TITLE, FetchStatus.FULLTEXT, absent, "0.99", 0, null, true);
            assertRepresentative(2, null, List.of(2L), empty, fullText);
        }
    }

    @Test
    void bodyTextWithoutFullTextStatusDoesNotReceivePriority() {
        var metadata = article(1, TITLE, FetchStatus.METADATA_ONLY, BODY.repeat(25), "0.99", 0, null, true);
        var fullText = article(2, TITLE, FetchStatus.FULLTEXT, BODY, "0.40", 1, null, true);

        assertRepresentative(2, null, List.of(2L), metadata, fullText);
    }

    @Test
    void newFullTextDoesNotAttachToAnExistingMetadataOnlyIssue() {
        var previous = article(1, TITLE, FetchStatus.METADATA_ONLY, null, "0.99", 0, 74L, false);
        var fullText = article(2, TITLE, FetchStatus.FULLTEXT, BODY, "0.40", 1, null, true);

        assertRepresentative(2, null, List.of(2L), previous, fullText);
    }

    @Test
    void newlyObservedSummaryCannotDisplaceAnExistingFullTextMember() {
        var previous = article(1, "[속보] " + TITLE, FetchStatus.FULLTEXT, BODY, "0.40", 0, 74L, false);
        var summary = article(2, TITLE, FetchStatus.METADATA_ONLY, null, "0.99", 1, null, true);

        assertRepresentative(1, 74L, List.of(1L), previous, summary);
    }

    @Test
    void metadataOnlyCandidatesDoNotCreateAnIssueOrRepresentative() {
        var breaking = article(1, "[속보] " + TITLE, FetchStatus.METADATA_ONLY, null, "0.99", 0, null, true);
        var ordinary = article(2, TITLE, FetchStatus.METADATA_ONLY, null, "0.40", 1, null, true);
        var stronger = article(3, TITLE, FetchStatus.METADATA_ONLY, null, "0.80", 2, null, true);

        for (List<ClusterArticle> input : List.of(List.of(breaking, ordinary, stronger), List.of(stronger, ordinary, breaking))) {
            ClusterPlan plan = clusterer.cluster(input);
            assertEquals(List.of(), plan.issues());
        }
    }

    @Test
    void fullTextPeersKeepTheExistingPreferenceForANonBreakingFollowUp() {
        var breaking = article(1, "[속보] " + TITLE, FetchStatus.FULLTEXT, BODY, "0.99", 0, null, true);
        var followup = article(2, TITLE, FetchStatus.FULLTEXT, BODY, "0.40", 1, null, true);

        assertRepresentative(2, null, List.of(1L, 2L), breaking, followup);
    }

    private void assertRepresentative(long expectedArticleId, Long expectedIssueId, List<Long> expectedMembers,
                                      ClusterArticle first, ClusterArticle second) {
        for (List<ClusterArticle> input : List.of(List.of(first, second), List.of(second, first))) {
            ClusterPlan plan = clusterer.cluster(input);
            assertEquals(1, plan.issues().size());
            assertEquals(expectedArticleId, plan.issues().getFirst().representativeArticleId());
            assertEquals(expectedIssueId, plan.issues().getFirst().existingIssueId());
            assertEquals(expectedMembers, plan.issues().getFirst().articleIds());
        }
    }

    private static ClusterArticle article(long id, String title, FetchStatus status, String body,
                                          String reliability, int hours, Long existingIssue, boolean observedInRun) {
        var time = TIME.plusHours(hours);
        return new ClusterArticle(id, 1, title, "새빛전자 메모리 설비 확장 계획을 발표했다.", body,
                status, id, "fixture-" + id, new BigDecimal(reliability), time, time,
                List.of(), null, null, existingIssue, observedInRun);
    }
}
