package com.example.be.domain.reports.comparison;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static com.example.be.domain.reports.comparison.ComparisonFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportComparisonEngineTest {
    private final ReportComparisonEngine engine = new ReportComparisonEngine();

    @Test void ranksAreInclusionNotEventBirthOrDeathAndTitlesDoNotImplyIdentity() {
        var plan = engine.prepare(work(snapshot(side(1, 10, "하반기 양산")), snapshot(side(2, 20, "10월 양산"))));
        assertTrue(plan.candidates().isEmpty());
        assertEquals(1, plan.result().items().size());
        assertEquals(ReportChanges.Type.NEWLY_INCLUDED, plan.result().items().getFirst().type());
        assertTrue(plan.result().items().getFirst().summary().contains("새 사건 발생을 뜻하지"));
        assertNull(plan.result().items().getFirst().previous());
    }

    @Test void identicalClaimsDoNotCallAgentAndDatesReflectActualGap() {
        var before = snapshot(side(1, 10, "하반기 양산"));
        var after = snapshot(side(1, 20, "하반기   양산"));
        var work = new ComparisonWork(101, LocalDate.of(2026, 9, 10), 100, LocalDate.of(2026, 9, 7), before, after, List.of());
        var plan = engine.prepare(work);
        assertTrue(plan.candidates().isEmpty());
        assertEquals(ReportChanges.Type.UNCHANGED, plan.result().items().getFirst().type());
        assertTrue(plan.result().notes().getFirst().contains("2026-09-07"));
    }

    @Test void provenMergePreservesOriginalIdentityWhileAmbiguousAncestryIsNotCompared() {
        var before = snapshot(side(1, 10, "하반기 양산"));
        var after = snapshot(side(2, 20, "10월 양산"));
        var merged = new ComparisonWork.IdentityLink(1, 2, "MERGED");
        var plan = engine.prepare(work(before, after, merged));
        assertEquals("MERGED", plan.candidates().getFirst().relation());
        assertEquals(1, plan.result().items().getFirst().previous().issueId());
        assertEquals(2, plan.result().items().getFirst().current().issueId());
        var ambiguous = engine.prepare(work(snapshot(side(1, 10, "하반기 양산"), side(3, 30, "양산 계획")), after,
                merged, new ComparisonWork.IdentityLink(3, 2, "MERGED")));
        assertTrue(ambiguous.candidates().isEmpty());
        assertEquals(ReportChanges.Type.UNDETERMINED, ambiguous.result().items().getFirst().type());
    }

    @Test void updatesRelationAloneIsNotMergeProof() {
        var plan = engine.prepare(work(snapshot(side(1, 10, "하반기 양산")), snapshot(side(2, 20, "10월 양산")),
                new ComparisonWork.IdentityLink(1, 2, "UPDATES")));
        assertTrue(plan.candidates().isEmpty());
        assertEquals(ReportChanges.Type.NEWLY_INCLUDED, plan.result().items().getFirst().type());
    }

    @Test void scopeChangesHoldAffectedTopicsButPermitCompatibleCommonTopics() {
        var before = snapshot(side(1, 10, "하반기 양산"));
        var after = snapshot(side(1, 20, "10월 양산")).withScopes(scopes("DRAM"));
        var plan = engine.prepare(work(before, after));
        assertTrue(plan.result().scopeChanged());
        assertTrue(plan.candidates().isEmpty());
        assertEquals(ReportChanges.Type.UNDETERMINED, plan.result().items().getFirst().type());
        assertNull(plan.result().items().getFirst().previous());
    }

    @Test void topicRenameAndEquivalentKeywordOrderDoNotSuppressComparison() {
        var oldScope = new com.example.be.domain.collection.entity.CollectionTopicSnapshot(1L, "옛 이름", "HBM",
                List.of(" HBM ", "DRAM"), List.of("공장", "생산"), List.of("광고"), 100, 1440);
        var newScope = new com.example.be.domain.collection.entity.CollectionTopicSnapshot(1L, "새 이름", "HBM",
                List.of("dram", "hbm"), List.of("생산", "공장"), List.of("광고"), 100, 1440);
        var before = snapshot(side(1, 10, "하반기 양산")).withScopes(List.of(
                new com.example.be.domain.reports.entity.ReportCollectionContext(10L, List.of(oldScope))));
        var after = snapshot(side(1, 20, "10월 양산")).withScopes(List.of(
                new com.example.be.domain.reports.entity.ReportCollectionContext(20L, List.of(newScope))));
        var plan = engine.prepare(work(before, after));
        assertFalse(plan.result().scopeChanged());
        assertEquals(1, plan.candidates().size());
    }

    @Test void sourceReferencesAndRefutationRelationAreRequiredForChangedAssessment() {
        var before = snapshot(side(1, 10, "하반기 양산"));
        var after = snapshot(side(1, 20, "10월 양산"));
        var plan = engine.prepare(work(before, after));
        var invalid = new ComparisonAssessment("issue-1", "UPDATED", "일정이 구체화되었습니다.", List.of("invented"), List.of("finding-20-claim-0"));
        assertEquals(ReportChanges.Type.UNDETERMINED, engine.assess(plan, List.of(invalid)).items().getFirst().type());
        var unsupportedRefutation = new ComparisonAssessment("issue-1", "REFUTATION", "반박 근거가 있습니다.",
                List.of("finding-10-claim-0"), List.of("finding-20-claim-0"));
        assertEquals(ReportChanges.Type.UNDETERMINED, engine.assess(plan, List.of(unsupportedRefutation)).items().getFirst().type());
        var valid = new ComparisonAssessment("issue-1", "UPDATED", "일정이 구체화되었습니다.",
                List.of("finding-10-claim-0"), List.of("finding-20-claim-0"));
        var result = engine.assess(plan, List.of(valid));
        assertEquals(ReportChanges.Type.UPDATED, result.items().getFirst().type());
        assertEquals("하반기 양산", result.items().getFirst().previous().claims().getFirst().evidence().getFirst().text());
        assertEquals(ReportChanges.Type.UNDETERMINED, engine.assess(plan, List.of(valid, valid)).items().getFirst().type());
    }

    @Test void refutationRequiresBothSnapshotsAndBothValidReferences() {
        var plan = engine.prepare(work(snapshot(side(1, 10, "공급 부족")), snapshot(side(2, 20, "부족하지 않다고 발표")),
                new ComparisonWork.IdentityLink(1, 2, "REFUTES")));
        var valid = new ComparisonAssessment("issue-2", "REFUTATION", "이전 전망에 반대되는 근거가 추가됐습니다.",
                List.of("finding-10-claim-0"), List.of("finding-20-claim-0"));
        assertEquals(ReportChanges.Type.REFUTATION, engine.assess(plan, List.of(valid)).items().getFirst().type());
    }

    @Test void oversizedInputsStayIntactForReadersAndAreNotPartiallyCompared() {
        var longText = "가".repeat(601);
        var before = snapshot(side(1, 10, longText));
        var after = snapshot(side(1, 20, "10월 양산"));
        var plan = engine.prepare(work(before, after));
        assertTrue(plan.candidates().isEmpty());
        assertEquals(longText, plan.result().items().getFirst().previous().claims().getFirst().text());
        assertEquals(ReportChanges.Type.UNDETERMINED, plan.result().items().getFirst().type());
        var original = side(1, 10, "이전 양산 계획");
        var claim = original.claims().getFirst();
        var manyEvidence = new ReportChanges.Side(1, 1, original.title(), original.summary(),
                List.of(new ReportChanges.Claim(claim.id(), claim.text(), java.util.stream.IntStream.range(0, 4)
                        .mapToObj(index -> new ReportChanges.Evidence(10, 110, 210, "제목", "https://example.com", index, "문장" + index)).toList())));
        assertTrue(engine.prepare(work(snapshot(manyEvidence), after)).candidates().isEmpty());
    }
}
