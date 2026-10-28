package com.example.be.domain.reports.coverage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportPublisherIdentityTest {
    @Test void providerNameAndOriginalHostIdentifyTheSamePublishingSite() {
        assertEquals(ReportPublisherIdentity.key("Reuters", "https://www.reuters.com/news/a"),
                ReportPublisherIdentity.key("reuters.com", "https://reuters.com/news/b"));
        assertEquals("site:reuters.com", ReportPublisherIdentity.key("REUTERS", "https://WWW.REUTERS.COM/news/c"));
        assertNotEquals(ReportPublisherIdentity.key("Example", "https://one.example.com/a"),
                ReportPublisherIdentity.key("Example", "https://two.example.com/b"), "unverified subdomains are not guessed aliases");
    }

    @Test void missingPublisherUnsafeUrlsAndAggregatorSubdomainsDoNotCreateFakeOutlets() {
        for (String url : new String[] {"https://n.news.naver.com/article/1", "https://www.bing.com/news/1",
                "https://news.google.com/articles/1", "https://news.google.co.kr/1", "javascript:alert(1)",
                "https://user:secret@reuters.com/1", null}) {
            assertNull(ReportPublisherIdentity.key("Reuters", url));
        }
        assertNull(ReportPublisherIdentity.key(null, "https://www.reuters.com/1"));
        assertNull(ReportPublisherIdentity.key("Google News", "https://www.reuters.com/1"));
        assertEquals("site:naver.com.example.org", ReportPublisherIdentity.key("매체", "https://naver.com.example.org/a"));
    }
}
