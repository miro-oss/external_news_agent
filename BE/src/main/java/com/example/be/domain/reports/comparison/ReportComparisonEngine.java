package com.example.be.domain.reports.comparison;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ReportComparisonEngine {
    private static final String UNCERTAIN = "저장된 근거만으로 의미 있는 변화를 확정하기 어렵습니다.";
    private static final ObjectMapper JSON = new ObjectMapper();

    public Plan prepare(ComparisonWork work) {
        var priorScopes = work.previous().scopesByTopic();
        var currentScopes = work.current().scopesByTopic();
        boolean scopeChanged = !priorScopes.equals(currentScopes);
        Set<Long> compatible = currentScopes.keySet().stream()
                .filter(id -> currentScopes.get(id).equals(priorScopes.get(id))).collect(Collectors.toSet());
        List<String> notes = new ArrayList<>();
        if (scopeChanged) notes.add("수집 조건이 달라진 주제는 비교를 보류하고 동일한 조건의 공통 주제만 비교했습니다.");
        if (ChronoUnit.DAYS.between(work.baseReportDate(), work.reportDate()) > 1) {
            notes.add(work.baseReportDate() + "부터 " + work.reportDate() + "까지의 보고서를 비교했습니다. 수집 날짜 사이에 공백이 있습니다.");
        }
        List<ReportChanges.Item> items = new ArrayList<>();
        List<ComparisonCandidate> candidates = new ArrayList<>();
        int requestLength = 2000;
        for (var currentIssue : work.current().issues()) {
            var current = currentIssue.side();
            String id = "issue-" + current.issueId();
            if (!compatible.contains(current.topicId())) {
                items.add(item(id, ReportChanges.Type.UNDETERMINED, "수집 조건이 달라 직접 비교하지 않았습니다.", null, current));
                continue;
            }
            List<ReportChanges.Side> same = work.previous().issues().stream().map(ReportComparisonSnapshot.Issue::side)
                    .filter(side -> side.topicId() == current.topicId() && side.issueId() == current.issueId()).toList();
            String relation = "SAME_ISSUE";
            List<ReportChanges.Side> matches = same;
            if (matches.isEmpty()) {
                relation = "MERGED";
                matches = linked(work, current, relation);
            }
            if (matches.isEmpty()) {
                relation = "REFUTES";
                matches = linked(work, current, relation);
            }
            boolean ambiguous = work.links().stream().anyMatch(link -> link.currentIssueId() == current.issueId()
                    && "AMBIGUOUS".equals(link.relation()));
            if (matches.size() > 1 || (same.isEmpty() && ambiguous)) {
                notes.add(current.title() + ": 이전 이슈와의 연결이 여러 개이거나 병합 계보를 확정할 수 없어 비교를 보류했습니다.");
                items.add(item(id, ReportChanges.Type.UNDETERMINED, UNCERTAIN, null, current));
            } else if (matches.isEmpty()) {
                items.add(item(id, ReportChanges.Type.NEWLY_INCLUDED,
                        "직전 보고서에 없고 이번 보고서에 포함되었습니다. 새 사건 발생을 뜻하지 않습니다.", null, current));
            } else {
                var previous = matches.getFirst();
                if (current.claims().isEmpty() || previous.claims().isEmpty()) {
                    items.add(item(id, ReportChanges.Type.UNDETERMINED, UNCERTAIN, previous, current));
                    continue;
                }
                if (claimTexts(previous).equals(claimTexts(current))) {
                    items.add(item(id, ReportChanges.Type.UNCHANGED,
                            "양쪽 보고서에 같은 주장이 포함되어 내용 변화를 확인하지 못했습니다.", previous, current));
                    continue;
                }
                var candidate = new ComparisonCandidate(id, relation, inputs(previous), inputs(current));
                int candidateLength = JSON.writeValueAsString(candidate).length();
                items.add(item(id, ReportChanges.Type.UNDETERMINED, UNCERTAIN, previous, current));
                if (!candidate.previous().isEmpty() && !candidate.current().isEmpty()
                        && candidates.size() < 50 && requestLength + candidateLength < 95000) {
                    candidates.add(candidate);
                    requestLength += candidateLength;
                } else {
                    notes.add(current.title() + ": 전체 근거가 비교 입력 상한을 넘거나 부족하여 의미 비교를 보류했습니다.");
                }
            }
        }
        var result = new ReportChanges(work.reportId(), work.reportDate(), work.baseReportId(), work.baseReportDate(),
                ReportChanges.Status.READY, ReportChanges.Status.READY.message, scopeChanged, notes, items);
        return new Plan(result, List.copyOf(candidates));
    }

    private List<ReportChanges.Side> linked(ComparisonWork work, ReportChanges.Side current, String relation) {
        Set<Long> previousIds = work.links().stream()
                .filter(link -> link.currentIssueId() == current.issueId() && relation.equals(link.relation()))
                .map(ComparisonWork.IdentityLink::previousIssueId).collect(Collectors.toSet());
        return work.previous().issues().stream().map(ReportComparisonSnapshot.Issue::side)
                .filter(side -> side.topicId() == current.topicId() && previousIds.contains(side.issueId())).toList();
    }

    public ReportChanges assess(Plan plan, List<ComparisonAssessment> assessments) {
        Map<String, ComparisonAssessment> byId = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        if (assessments != null) for (var assessment : assessments) {
            if (assessment == null) continue;
            if (byId.putIfAbsent(assessment.candidateId(), assessment) != null) duplicateIds.add(assessment.candidateId());
        }
        Map<String, ComparisonCandidate> candidates = plan.candidates().stream()
                .collect(Collectors.toMap(ComparisonCandidate::id, value -> value));
        List<ReportChanges.Item> items = plan.result().items().stream().map(item -> {
            var candidate = candidates.get(item.id());
            var assessment = byId.get(item.id());
            if (candidate == null || assessment == null || duplicateIds.contains(item.id())) return item;
            ReportChanges.Type type;
            try { type = ReportChanges.Type.valueOf(assessment.type()); }
            catch (RuntimeException invalid) { return item; }
            if (type == ReportChanges.Type.NEWLY_INCLUDED || assessment.summary() == null
                    || assessment.summary().isBlank() || assessment.summary().length() > 1600
                    || !validReferences(candidate.previous(), assessment.previousClaimIds())
                    || !validReferences(candidate.current(), assessment.currentClaimIds())) return item;
            if ((type == ReportChanges.Type.UPDATED || type == ReportChanges.Type.REFUTATION)
                    && (assessment.previousClaimIds().isEmpty() || assessment.currentClaimIds().isEmpty())) return item;
            if (type == ReportChanges.Type.REFUTATION && !"REFUTES".equals(candidate.relation())) return item;
            return item(item.id(), type, assessment.summary(),
                    referenced(item.previous(), assessment.previousClaimIds()), referenced(item.current(), assessment.currentClaimIds()));
        }).toList();
        var result = plan.result();
        return new ReportChanges(result.reportId(), result.reportDate(), result.baseReportId(), result.baseReportDate(),
                result.status(), result.message(), result.scopeChanged(), result.notes(), items);
    }

    private ReportChanges.Side referenced(ReportChanges.Side side, List<String> ids) {
        if (ids.isEmpty()) return side;
        return new ReportChanges.Side(side.issueId(), side.topicId(), side.title(), side.summary(),
                side.claims().stream().filter(claim -> ids.contains(claim.id())).toList());
    }

    private boolean validReferences(List<ComparisonClaimInput> claims, List<String> ids) {
        return ids != null && new HashSet<>(ids).size() == ids.size()
                && claims.stream().map(ComparisonClaimInput::id).collect(Collectors.toSet()).containsAll(ids);
    }

    private Set<String> claimTexts(ReportChanges.Side side) {
        return side.claims().stream().map(claim -> claim.text().trim().replaceAll("\\s+", " "))
                .collect(Collectors.toSet());
    }

    private List<ComparisonClaimInput> inputs(ReportChanges.Side side) {
        // Keep full stored text. A sentence which exceeds the contract is not clipped into a different claim.
        if (side.claims().size() > 6 || side.claims().stream().anyMatch(claim -> claim.text().length() > 600
                || claim.evidence().isEmpty() || claim.evidence().size() > 3
                || claim.evidence().stream().anyMatch(evidence -> evidence.text() == null
                || evidence.text().isBlank() || evidence.text().length() > 600))) return List.of();
        return side.claims().stream().map(claim -> new ComparisonClaimInput(claim.id(), claim.text(),
                claim.evidence().stream().map(ReportChanges.Evidence::text).toList())).toList();
    }

    private ReportChanges.Item item(String id, ReportChanges.Type type, String summary,
                                    ReportChanges.Side previous, ReportChanges.Side current) {
        return new ReportChanges.Item(id, type, current.title(), summary, previous, current);
    }

    public record Plan(ReportChanges result, List<ComparisonCandidate> candidates) { }
}
