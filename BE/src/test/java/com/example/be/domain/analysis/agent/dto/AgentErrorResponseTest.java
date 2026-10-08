package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentErrorResponseTest {

    @Test
    void readsObservedMetadataSeparatelyFromUsage() {
        AgentErrorResponse response = error(Map.of(
                "executionMetadata", metadata(),
                "usage", Map.of("inputTokens", 10, "outputTokens", 3,
                        "costUsd", "0.001", "credits", 0)));

        assertEquals(new AgentClientException.ExecutionMetadata(
                "openai", "observed-model", "insight.ko.v2", "AGENT_ERROR"),
                response.executionMetadata());
        assertEquals(10L, response.usage().inputTokens());
    }

    @Test
    void acceptsKnownPromptWithUnobservedProviderAndModel() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", null);
        metadata.put("model", null);
        metadata.put("promptVersion", "insight.ko.v2");
        metadata.put("source", "AGENT_ERROR");

        assertEquals(new AgentClientException.ExecutionMetadata(
                null, null, "insight.ko.v2", "AGENT_ERROR"),
                error(Map.of("executionMetadata", metadata)).executionMetadata());
    }

    @Test
    void rejectsInvalidMetadataWithoutDiscardingUsage() {
        List<Map<String, Object>> invalid = List.of(
                changed("provider", 123),
                changed("provider", "x".repeat(31)),
                changed("model", "x".repeat(101)),
                changed("promptVersion", "x".repeat(51)),
                changed("provider", " openai"),
                changed("model", "model\nvalue"),
                changed("promptVersion", ""),
                changed("source", "CONFIGURED"),
                changed("usageCompleteness", "complete"),
                changed("usageCompleteness", 123),
                changed("unapprovedField", "do not store"));

        for (Map<String, Object> metadata : invalid) {
            AgentErrorResponse response = error(Map.of(
                    "executionMetadata", metadata,
                    "usage", Map.of("inputTokens", 10)));
            assertNull(response.executionMetadata());
            assertEquals(10L, response.usage().inputTokens());
        }
    }

    @Test
    void readsUsageCompletenessAndDefaultsOlderMetadataToUnknown() {
        for (String completeness : List.of("COMPLETE", "PARTIAL", "UNKNOWN")) {
            AgentErrorResponse response = error(Map.of(
                    "executionMetadata", changed("usageCompleteness", completeness)));
            assertEquals(completeness, response.executionMetadata().usageCompleteness());
        }
        assertEquals("UNKNOWN", error(Map.of("executionMetadata", metadata()))
                .executionMetadata().usageCompleteness());
        assertEquals("UNKNOWN", new AgentClientException.ExecutionMetadata(
                "openai", "observed-model", "insight.ko.v2", "AGENT_ERROR")
                .usageCompleteness());
    }

    @Test
    void missingOrMalformedMetadataKeepsLegacyErrorCompatible() {
        assertNull(new AgentErrorResponse(null).executionMetadata());
        assertNull(error(null).executionMetadata());
        assertNull(error(List.of("validation details")).executionMetadata());
        assertNull(error(Map.of("executionMetadata", "invalid")).executionMetadata());
        assertNull(error(Map.of("executionMetadata", Map.of("provider", "openai")))
                .executionMetadata());
        assertNull(new AgentClientException("ERROR", "legacy error").getExecutionMetadata());
    }

    @Test
    void malformedUsageDoesNotDiscardValidMetadata() {
        AgentErrorResponse response = error(Map.of(
                "executionMetadata", metadata(),
                "usage", Map.of("inputTokens", "invalid")));

        assertNull(response.usage());
        assertEquals("observed-model", response.executionMetadata().model());
    }

    @Test
    void readsBoundedValidationFailureWithoutDiscardingUsageOrExecutionMetadata() {
        var response = error(Map.of("validationFailure", validation(),
                "executionMetadata", metadata(), "usage", Map.of("inputTokens", 10)));

        assertEquals(new AgentClientException.ValidationFailure("MAP-002", 2,
                "OutputValidationError", 2, List.of("report_assessment_draft_invalid", "report_fact_mismatch")),
                response.validationFailure());
        assertEquals(10L, response.usage().inputTokens());
        assertEquals("observed-model", response.executionMetadata().model());
        assertNull(new AgentClientException("SCHEMA_VIOLATION", "legacy").getValidationFailure());
    }

    @Test
    void preservesDistinctSemanticKindsAndSafeLocations() {
        for (String kind : List.of("report_fact_contradiction", "report_evidence_insufficient",
                "report_expression_policy", "report_evidence_reference_invalid",
                "report_output_shape", "report_output_parse", "report_output_unlocated")) {
            var failure = new HashMap<>(validation());
            failure.put("errorKinds", List.of(kind));
            failure.put("issuesTruncated", false);
            failure.put("issues", List.of(Map.of("audience", "CHIP_MAKER",
                    "field", "assessments[7869].reason", "errorKind", kind,
                    "claimIds", List.of("7869:1"))));
            var parsed = error(Map.of("validationFailure", failure)).validationFailure();
            assertEquals(List.of(kind), parsed.errorKinds());
            assertEquals(kind, parsed.issues().getFirst().errorKind());
        }
    }

    @Test
    void unrecognizedKindsAreOmittedWithoutInventingACause() {
        var failure = new HashMap<>(validation());
        failure.put("errorKinds", List.of("private-kind\nforged", "report_fact_mismatch", "report_fact_mismatch"));
        assertEquals(List.of("report_fact_mismatch"), error(Map.of("validationFailure", failure))
                .validationFailure().errorKinds());
        failure.put("errorKinds", List.of("private-kind"));
        assertEquals(List.of(), error(Map.of("validationFailure", failure)).validationFailure().errorKinds());
    }

    @Test
    void rejectsMalformedValidationContextWithoutDiscardingUsage() {
        var invalid = List.of(
                changedValidation("stage", "MAP-002\nforged"),
                changedValidation("stage", "PRIVATE_STAGE"),
                changedValidation("stage", "MAP-1000"),
                changedValidation("attempt", 3),
                changedValidation("attempt", "2"),
                changedValidation("attempt", 1.5),
                changedValidation("errorType", "PrivateDynamicException"),
                changedValidation("errorCount", -1),
                changedValidation("errorCount", 1_000_001),
                changedValidation("errorKinds", List.of("value_error", "value_error", "value_error",
                        "value_error", "value_error", "value_error")),
                changedValidation("errorKinds", List.of(123)),
                changedValidation("message", "private response"));
        for (var failure : invalid) {
            var response = error(Map.of("validationFailure", failure, "usage", Map.of("inputTokens", 10)));
            assertNull(response.validationFailure());
            assertEquals(10L, response.usage().inputTokens());
        }
    }

    @Test
    void providerErrorsCannotClaimOldValidationDiagnostics() {
        var response = new AgentErrorResponse(new AgentErrorResponse.ErrorDetail(
                "PROVIDER_UNAVAILABLE", "failed repair provider", Map.of("validationFailure", validation())));
        assertNull(response.validationFailure());
        assertNull(new AgentErrorResponse(null).validationFailure());
        assertNull(error(null).validationFailure());
        assertNull(error(Map.of("validationFailure", "invalid")).validationFailure());
    }

    @Test
    void readsClosedReduceIssuesAndLegacyMessagesRemainCompatible() {
        var failure = reduceIssues(List.of(issue()), false);
        var parsed = error(Map.of("validationFailure", failure)).validationFailure();
        assertEquals(List.of(new AgentClientException.ValidationIssue("CHIP_MAKER",
                "implications[1].falsifiedBy", "report_fact_mismatch", List.of("7869:1"))), parsed.issues());
        assertEquals(false, parsed.issuesTruncated());
        assertEquals(List.of(), error(Map.of("validationFailure", validation())).validationFailure().issues());
        assertEquals(false, error(Map.of("validationFailure", validation())).validationFailure().issuesTruncated());
    }

    @Test
    void readsClosedMapAndReviewFindingLocationsAndRejectsStageMixing() {
        var fields = List.of("assessments[7869]", "assessments[7869].reason",
                "assessments[7869].basisClaimIds", "assessments[7869].decision.connection.relation",
                "assessments[7869].decision.connection.work", "assessments[7869].decision.connection.basis",
                "assessments[7869].decision.effect.basis.claimId",
                "assessments[7869].decision.timing.basis.sourceSpanId",
                "assessments[7869].axes.urgency", "assessments[7869].decision.connection.condition",
                "assessments[7869].decision.effect.impactScope", "assessments[7869].decision.timing.urgencyState");
        for (var stage : List.of("MAP", "MAP-001", "REVIEW", "REVIEW-001")) {
            for (var field : fields) {
                var failure = reduceIssues(List.of(changedIssue("field", field)), false);
                failure.put("stage", stage);
                var parsed = error(Map.of("validationFailure", failure)).validationFailure();
                assertEquals(List.of(new AgentClientException.ValidationIssue("CHIP_MAKER", field,
                        "report_fact_mismatch", List.of("7869:1"))), parsed.issues());
                failure.put("stage", "REDUCE-001");
                assertNull(error(Map.of("validationFailure", failure)).validationFailure());
            }
        }
    }

    @Test
    void readsReduceUnitLocationsForIncompleteOptionalItems() {
        for (var field : List.of("overview[0]", "implications[4]", "watchItems[4]")) {
            var failure = reduceIssues(List.of(changedIssue("field", field)), false);
            var parsed = error(Map.of("validationFailure", failure)).validationFailure();
            assertEquals(field, parsed.issues().getFirst().field());
        }
    }

    @Test
    void rejectsUnboundedOrInjectedFindingPathsWithoutLosingUsage() {
        for (var field : List.of("assessments[0].reason", "assessments[-1].reason", "assessments[01].reason",
                "assessments[１].reason", "assessments[" + "1".repeat(20) + "].reason",
                "assessments[PRIVATE].reason", "assessments[7869].reason\nPRIVATE",
                "assessments[7869].PRIVATE", "assessments[7869].decision.connection.basis.PRIVATE",
                "assessments[7869].reason nativeFields=PRIVATE")) {
            var failure = reduceIssues(List.of(changedIssue("field", field)), false);
            failure.put("stage", "MAP-001");
            var response = error(Map.of("validationFailure", failure, "usage", Map.of("inputTokens", 10)));
            assertNull(response.validationFailure());
            assertEquals(10L, response.usage().inputTokens());
        }
    }

    @Test
    void rejectsIssueProseAndUnboundedMetadataWithoutLosingUsage() {
        var invalidIssues = List.of(
                changedIssue("audience", "CHIP_MAKER\nforged"),
                changedIssue("field", "implications[5].text"),
                changedIssue("field", "overview[-1].text"),
                changedIssue("field", "private-prose"),
                changedIssue("field", "watchItems[0].private-prose"),
                changedIssue("errorKind", "report_private_prose"),
                changedIssue("errorKind", "value_error"),
                changedIssue("claimIds", List.of("7869:1\nforged")),
                changedIssue("claimIds", List.of("0:0")),
                changedIssue("claimIds", List.of("1".repeat(20) + ":0")),
                changedIssue("claimIds", List.of(123)),
                changedIssue("claimIds", java.util.Collections.nCopies(9, "7869:1")),
                changedIssue("message", "private prose"));
        for (var issue : invalidIssues) {
            var response = error(Map.of("validationFailure", reduceIssues(List.of(issue), false),
                    "usage", Map.of("inputTokens", 10)));
            assertNull(response.validationFailure());
            assertEquals(10L, response.usage().inputTokens());
        }
        assertNull(error(Map.of("validationFailure", reduceIssues(java.util.Collections.nCopies(9, issue()), true)))
                .validationFailure());
        var mapFailure = reduceIssues(List.of(issue()), false);
        mapFailure.put("stage", "MAP-001");
        assertNull(error(Map.of("validationFailure", mapFailure)).validationFailure());
        var missingFlag = reduceIssues(List.of(issue()), false);
        missingFlag.remove("issuesTruncated");
        assertNull(error(Map.of("validationFailure", missingFlag)).validationFailure());
        var wrongFlag = reduceIssues(List.of(issue()), false);
        wrongFlag.put("issuesTruncated", "true");
        assertNull(error(Map.of("validationFailure", wrongFlag)).validationFailure());
    }

    @Test
    void preservesBoundedTruncationMarkerAndCopiesIssueLists() {
        var refs = new java.util.ArrayList<>(List.of("7869:1"));
        var issue = new AgentClientException.ValidationIssue("CHIP_MAKER", "headline", "string_too_long", refs);
        refs.clear();
        assertEquals(List.of("7869:1"), issue.claimIds());
        var parsed = error(Map.of("validationFailure", reduceIssues(java.util.Collections.nCopies(8, issue()), true)))
                .validationFailure();
        assertEquals(8, parsed.issues().size());
        assertEquals(true, parsed.issuesTruncated());
    }

    private Map<String, Object> issue() {
        return Map.of("audience", "CHIP_MAKER", "field", "implications[1].falsifiedBy",
                "errorKind", "report_fact_mismatch", "claimIds", List.of("7869:1"));
    }

    private Map<String, Object> changedIssue(String key, Object value) {
        var result = new HashMap<>(issue());
        result.put(key, value);
        return result;
    }

    private Map<String, Object> reduceIssues(List<?> issues, boolean truncated) {
        var result = new HashMap<>(validation());
        result.put("stage", "REDUCE-001");
        result.put("issues", issues);
        result.put("issuesTruncated", truncated);
        return result;
    }

    private Map<String, Object> validation() {
        return Map.of("stage", "MAP-002", "attempt", 2, "errorType", "OutputValidationError",
                "errorCount", 2, "errorKinds", List.of("report_assessment_draft_invalid", "report_fact_mismatch"));
    }

    private Map<String, Object> changedValidation(String key, Object value) {
        var result = new HashMap<>(validation());
        result.put(key, value);
        return result;
    }

    private Map<String, Object> metadata() {
        return Map.of("provider", "openai", "model", "observed-model",
                "promptVersion", "insight.ko.v2", "source", "AGENT_ERROR");
    }

    private Map<String, Object> changed(String key, Object value) {
        Map<String, Object> changed = new HashMap<>(metadata());
        changed.put(key, value);
        return changed;
    }

    private AgentErrorResponse error(Object details) {
        return new AgentErrorResponse(new AgentErrorResponse.ErrorDetail(
                "SCHEMA_VIOLATION", "invalid output", details));
    }
}
