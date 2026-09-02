package com.example.be.domain.articles.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ArticleQueryServiceAudienceTest {

    private final ArticleQueryServiceImpl service = new ArticleQueryServiceImpl(
            mock(FindingRepository.class), mock(ArticleRepository.class),
            mock(IssueArticleRepository.class),
            com.example.be.domain.analysis.service.SensitivityCalculator.defaults());

    @Test
    void rejectsUnknownAudienceWithAudience400() {
        AudienceException exception = assertThrows(
                AudienceException.class,
                () -> query("OPERATOR", null));

        assertEquals("AUDIENCE400", exception.getCode().getCode());
    }

    @Test
    void rejectsMinimumRelevanceWithoutAudience() {
        assertThrows(AudienceException.class, () -> query(null, "high"));
    }

    @Test
    void rejectsUnknownMinimumAudienceRelevance() {
        assertThrows(AudienceException.class, () -> query("CHIP_MAKER", "important"));
    }

    private void query(String audience, String minimum) {
        service.getArticles(
                null, null, null, null, null, null, null, null,
                audience, minimum, null, null, "PUBLISHED_DESC", 0, 20);
    }
}
