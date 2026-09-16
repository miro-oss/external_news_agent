package com.example.be.domain.collection.content;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import static com.example.be.domain.collection.content.ArticleContentFailureReason.*;
import static org.junit.jupiter.api.Assertions.*;

class ArticleContentResultTest {

    @Test
    void legacyFactoriesKeepTheirStatusAndReceiveDiagnosticDefaults() {
        assertEquals(new ArticleContentResult(FetchStatus.FULLTEXT, "article body", NONE),
                ArticleContentResult.fullText("article body"));
        assertEquals(new ArticleContentResult(FetchStatus.FULLTEXT_BLOCKED, null, HTTP_ACCESS_DENIED),
                ArticleContentResult.blocked());
        assertEquals(new ArticleContentResult(FetchStatus.ROBOTS_DISALLOWED, null, ROBOTS_DISALLOWED),
                ArticleContentResult.robotsDisallowed());
        assertEquals(new ArticleContentResult(FetchStatus.FETCH_FAILED, null, UNKNOWN),
                ArticleContentResult.failed());
        assertEquals(NONE, new ArticleContentResult(FetchStatus.METADATA_ONLY, null).reason());
    }

    @Test
    void specificFailureDoesNotChangePersistedStatusOrClaimABody() {
        ArticleContentResult result = ArticleContentResult.failed(TLS_VALIDATION);

        assertEquals(FetchStatus.FETCH_FAILED, result.status());
        assertEquals(TLS_VALIDATION, result.reason());
        assertNull(result.body());
        assertFalse(result.hasBody());
        assertTrue(ArticleContentResult.fullText("body").hasBody());
    }
}
