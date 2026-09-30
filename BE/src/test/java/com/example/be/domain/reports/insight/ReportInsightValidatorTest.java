package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static com.example.be.domain.reports.insight.ReportInsightTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportInsightValidatorTest {
    private final ReportInsightValidator validator = new ReportInsightValidator();
    @Test void acceptsCompleteReferencedInterpretationsAndEmptyOptionalInterpretations() {
        assertDoesNotThrow(() -> validator.validate(response(), request()));
    }
    @Test void rejectsOmittedDuplicateAndUnknownFindings() {
        for (var assessments : List.of(List.of(assessment(50, 3, 2, 2)),
                List.of(assessment(50, 3, 2, 2), assessment(50, 3, 2, 2)),
                List.of(assessment(50, 3, 2, 2), assessment(99, 3, 2, 2)))) {
            assertThrows(AgentClientException.class, () -> validator.validate(response(assessments, BigDecimal.ONE), request()));
        }
    }
    @Test void rejectsCrossFindingEvidenceAndUnsupportedNovelty() {
        var cross = new AgentReportInsightResponse.Assessment(50L, "이유", List.of("40:0"),
                new AgentReportInsightResponse.Axes(3, 2, 2, null));
        var novelty = new AgentReportInsightResponse.Assessment(50L, "이유", List.of("50:0"),
                new AgentReportInsightResponse.Axes(3, 2, 2, 1));
        for (var invalid : List.of(cross, novelty)) assertThrows(AgentClientException.class,
                () -> validator.validate(response(List.of(invalid, assessment(40, 3, 2, 2)), BigDecimal.ONE), request()));
    }
    @Test void allowsAbstentionWithoutBasisButRejectsScoresWithoutBasis() {
        var abstain = new AgentReportInsightResponse.Assessment(50L, "근거가 부족하다.", List.of(),
                new AgentReportInsightResponse.Axes(null, null, null, null));
        assertDoesNotThrow(() -> validator.validate(response(List.of(abstain, assessment(40, 3, 2, 2)), BigDecimal.ONE), request()));
        var scored = new AgentReportInsightResponse.Assessment(50L, "근거가 부족하다.", List.of(),
                new AgentReportInsightResponse.Axes(0, null, null, null));
        assertThrows(AgentClientException.class, () -> validator.validate(response(List.of(scored, assessment(40, 3, 2, 2)), BigDecimal.ONE), request()));
    }
    @Test void rejectsNegativeObservedCredits() {
        assertThrows(AgentClientException.class, () -> validator.validate(response(response().insights().getFirst().assessments(),
                BigDecimal.valueOf(-1)), request()));
    }
}
