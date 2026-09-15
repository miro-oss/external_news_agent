package com.example.be.domain.analysis.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueAnalysisContextTest {

    @Test
    void retainsOnlyFullTextArticlesForEveryIssueLookupAndPrimaryTarget() {
        Article representative = article(10L, "대표 전문", FetchStatus.FULLTEXT);
        Article member = article(11L, "멤버 전문", FetchStatus.FULLTEXT);
        Article metadata = article(12L, null, FetchStatus.METADATA_ONLY);
        Article blank = article(13L, " \n\t", FetchStatus.FULLTEXT);
        Article failed = article(14L, "재수집 전 전문", FetchStatus.FETCH_FAILED);
        Article missing = article(15L, null, FetchStatus.FULLTEXT);

        IssueAnalysisContext context = new IssueAnalysisContext(88L, 10L,
                Arrays.asList(member, metadata, blank, failed, null, missing, representative),
                Set.of(10L, 11L, 12L, 13L, 14L, 15L, 99L));

        assertTrue(context.present());
        assertEquals(List.of(representative, member), context.articles());
        assertEquals(List.of(member), context.membersExcept(10L));
        assertEquals(Set.of(10L, 11L), context.primaryTargetArticleIds());
        assertEquals(member, context.article(11L));
        assertNull(context.article(12L));
        assertNull(context.article(13L));
        assertNull(context.article(14L));
        assertNull(context.article(15L));
    }

    @Test
    void unavailableRepresentativeDoesNotMakeAnIssueReadyForComparison() {
        Article metadata = article(10L, null, FetchStatus.METADATA_ONLY);
        Article member = article(11L, "멤버 전문", FetchStatus.FULLTEXT);

        IssueAnalysisContext context = new IssueAnalysisContext(88L, 10L, List.of(metadata, member));

        assertFalse(context.present());
        assertNull(context.representativeArticleId());
        assertEquals(List.of(member), context.articles());
        assertTrue(context.membersExcept(10L).isEmpty());
    }

    private Article article(Long id, String body, FetchStatus status) {
        return Article.builder().id(id).body(body).fetchStatus(status).build();
    }
}
