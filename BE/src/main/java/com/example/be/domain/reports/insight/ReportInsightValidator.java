package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ReportInsightValidator {
    public void validate(AgentReportInsightResponse response, AgentReportInsightRequest request) {
        if (response == null || response.meta() == null || response.meta().truncated()
                || !ReportInsightService.PROMPT_VERSION.equals(response.meta().promptVersion())
                || !text(response.meta().provider(), 30) || !text(response.meta().model(), 100)
                || !Set.of("openai", "gemini", "mindlogic-claude", "mock").contains(response.meta().provider())
                || response.meta().inputTokens() == null || response.meta().inputTokens() < 0
                || response.meta().outputTokens() == null || response.meta().outputTokens() < 0
                || response.meta().costUsd() == null || response.meta().costUsd().signum() < 0
                || response.meta().credits() == null || response.meta().credits().signum() < 0
                || response.insights() == null || response.insights().size() != request.audiences().size()) fail();
        Set<String> expectedAudiences = new HashSet<>(request.audiences());
        Set<String> returnedAudiences = new HashSet<>();
        Map<Long, Set<String>> byFinding = request.findings().stream().collect(Collectors.toMap(
                AgentReportInsightRequest.FindingPayload::id, finding -> finding.claims().stream()
                        .map(AgentReportInsightRequest.ClaimPayload::id).collect(Collectors.toSet())));
        Set<String> allClaims = byFinding.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        for (var insight : response.insights()) {
            if (insight == null || !expectedAudiences.contains(insight.audience())
                    || !returnedAudiences.add(insight.audience()) || !text(insight.headline(), 200)
                    || insight.overview() == null || insight.overview().size() > 3
                    || insight.assessments() == null || insight.assessments().size() != byFinding.size()
                    || insight.implications() == null || insight.implications().size() > 5
                    || insight.watchItems() == null || insight.watchItems().size() > 5) fail();
            Set<Long> assessed = new HashSet<>();
            for (var assessment : insight.assessments()) {
                if (assessment == null || !byFinding.containsKey(assessment.findingId())
                        || !assessed.add(assessment.findingId()) || !text(assessment.reason(), 500)
                        || assessment.axes() == null) fail();
                var axes = assessment.axes();
                if (!axis(axes.directness()) || !axis(axes.impact()) || !axis(axes.urgency()) || axes.novelty() != null) fail();
                refs(assessment.basisClaimIds(), byFinding.get(assessment.findingId()),
                        axes.directness() == null && axes.impact() == null && axes.urgency() == null);
            }
            for (var overview : insight.overview()) {
                if (overview == null || !text(overview.text(), 600) || !text(overview.assumption(), 500)) fail();
                refs(overview.basisClaimIds(), allClaims, false);
            }
            for (var implication : insight.implications()) {
                if (implication == null || !text(implication.text(), 700) || !text(implication.mechanism(), 500)
                        || !text(implication.assumption(), 500) || !text(implication.falsifiedBy(), 500)) fail();
                refs(implication.basisClaimIds(), allClaims, false);
            }
            for (var watch : insight.watchItems()) {
                if (watch == null || !text(watch.topic(), 200) || !text(watch.indicator(), 400) || !text(watch.trigger(), 400)) fail();
                refs(watch.basisClaimIds(), allClaims, false);
            }
        }
    }

    private void refs(List<String> refs, Set<String> known, boolean allowEmpty) {
        if (refs == null || (!allowEmpty && refs.isEmpty()) || new HashSet<>(refs).size() != refs.size()
                || !known.containsAll(refs)) fail();
    }
    private boolean text(String value, int max) {
        return StringUtils.hasText(value) && value.codePointCount(0, value.length()) <= max;
    }
    private boolean axis(Integer value) { return value == null || (value >= 0 && value <= 3); }
    private void fail() { throw new AgentClientException("SCHEMA_VIOLATION", "리포트 관점 인사이트 응답 또는 근거 참조가 올바르지 않습니다."); }
}
