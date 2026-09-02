package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueStance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IssueStanceClassifierTest {

    private final IssueStanceClassifier classifier = new IssueStanceClassifier();

    @Test
    void classifiesExplicitCorrectionAsRetraction() {
        IssueStanceClassifier.Result result = classifier.classify(
                article(1L, "삼성전자 HBM4 양산 확정", "연내 양산을 시작한다."),
                article(2L, "삼성전자 HBM4 보도 정정", "양산 확정 보도는 오보라고 밝혔다."));

        assertEquals(IssueStance.RETRACTS, result.stance());
        assertEquals(new BigDecimal("0.850"), result.confidence());
    }

    @Test
    void classifiesConflictingNumbersAsDispute() {
        IssueStanceClassifier.Result result = classifier.classify(
                article(1L, "삼성전자 3조원 투자", "HBM 설비에 3조원을 투자한다."),
                article(2L, "삼성전자 5조원 투자", "HBM 설비 투자액은 5조원이다."));

        assertEquals(IssueStance.DISPUTES, result.stance());
        assertEquals(new BigDecimal("0.850"), result.confidence());
    }

    @Test
    void givesHighConfidenceToSharedFactTokens() {
        IssueStanceClassifier.Result result = classifier.classify(
                article(1L, "삼성전자 HBM4 양산 확대", "HBM4 양산 확대 계획을 발표했다."),
                article(2L, "삼성전자 HBM4 양산 확대 발표", "HBM4 양산 확대 계획을 공개했다."));

        assertEquals(IssueStance.SUPPORTS, result.stance());
        assertEquals(new BigDecimal("0.850"), result.confidence());
    }

    private Article article(Long id, String title, String summary) {
        return Article.builder().id(id).title(title).summary(summary).build();
    }
}
