package com.example.be.domain.analysis.agent.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public class AgentClientException extends RuntimeException {

    private final String code;
    private final Usage usage;
    private final TimeoutPhase timeoutPhase;
    private final ExecutionMetadata executionMetadata;
    private final ValidationFailure validationFailure;

    public AgentClientException(String code, String message) {
        this(code, message, null, null, TimeoutPhase.NONE);
    }

    public AgentClientException(String code, String message, Throwable cause) {
        this(code, message, cause, null, TimeoutPhase.NONE);
    }

    public AgentClientException(String code, String message, Throwable cause, Usage usage) {
        this(code, message, cause, usage, TimeoutPhase.NONE);
    }

    public AgentClientException(String code,
                                String message,
                                Throwable cause,
                                Usage usage,
                                TimeoutPhase timeoutPhase) {
        this(code, message, cause, usage, timeoutPhase, null);
    }

    public AgentClientException(String code,
                                String message,
                                Throwable cause,
                                Usage usage,
                                TimeoutPhase timeoutPhase,
                                ExecutionMetadata executionMetadata) {
        this(code, message, cause, usage, timeoutPhase, executionMetadata, null);
    }

    public AgentClientException(String code, String message, Throwable cause, Usage usage,
                                TimeoutPhase timeoutPhase, ExecutionMetadata executionMetadata,
                                ValidationFailure validationFailure) {
        super(message, cause);
        this.code = code;
        this.usage = usage;
        this.timeoutPhase = timeoutPhase;
        this.executionMetadata = executionMetadata;
        this.validationFailure = validationFailure;
    }

    public String getCode() {
        return code;
    }

    public Usage getUsage() {
        return usage;
    }

    public ExecutionMetadata getExecutionMetadata() {
        return executionMetadata;
    }

    public ValidationFailure getValidationFailure() {
        return validationFailure;
    }

    public boolean isConnectTimeout() {
        return timeoutPhase == TimeoutPhase.CONNECT;
    }

    public boolean isReadTimeout() {
        return timeoutPhase == TimeoutPhase.READ;
    }

    public TimeoutPhase getTimeoutPhase() {
        return timeoutPhase;
    }

    public enum TimeoutPhase {
        NONE,
        CONNECT,
        READ
    }

    public record Usage(Long inputTokens,
                        Long outputTokens,
                        BigDecimal costUsd,
                        BigDecimal credits) {
    }

    /** Bounded diagnostics only; never response text, exception prose or arbitrary identifiers. */
    public record ValidationFailure(String stage, int attempt, String errorType, int errorCount,
                                    List<String> errorKinds, List<ValidationIssue> issues,
                                    boolean issuesTruncated) {
        public ValidationFailure(String stage, int attempt, String errorType, int errorCount,
                                 List<String> errorKinds) {
            this(stage, attempt, errorType, errorCount, errorKinds, List.of(), false);
        }
        private static final Set<String> TYPES = Set.of(
                "OutputValidationError", "ValidationError", "JsonObjectParseError", "ValueError");
        private static final Set<String> KINDS = Set.of((
                "report_assessment_draft_invalid report_assessment_invalid report_assessment_truncated_prefix "
                + "report_assumption_unconfirmed report_fact_mismatch report_falsification_direction "
                + "report_fact_contradiction report_evidence_insufficient report_expression_policy "
                + "report_evidence_reference_invalid report_output_shape report_output_parse report_output_unlocated "
                + "report_falsification_missing_observation report_synthesis_empty report_synthesis_information_gap "
                + "report_synthesis_invalid report_synthesis_metadata_only report_synthesis_placeholder "
                + "report_synthesis_reference_gap report_synthesis_source_binding report_synthesis_stage_overreach "
                + "report_synthesis_subject_mismatch report_work_approval_prerequisite_unsupported "
                + "report_work_certification_prerequisite_unsupported report_work_compatibility_procedure_unsupported "
                + "report_work_cooling_procedure_unsupported report_work_inspection_prerequisite_unsupported "
                + "report_work_organization_prerequisite_unsupported report_work_physical_module_unsupported "
                + "report_work_specification_procedure_unsupported json_object_parse "
                // Pydantic's closed built-in ErrorType names; custom error names are excluded.
                + "no_such_attribute json_invalid json_type needs_python_object recursion_loop missing "
                + "frozen_field frozen_instance extra_forbidden invalid_key get_attribute_error model_type "
                + "model_attributes_type dataclass_type dataclass_exact_type default_factory_not_called "
                + "none_required greater_than greater_than_equal less_than less_than_equal multiple_of "
                + "finite_number too_short too_long iterable_type iteration_error string_type string_sub_type "
                + "string_unicode string_too_short string_too_long string_pattern_mismatch string_not_ascii "
                + "enum dict_type mapping_type list_type tuple_type set_type set_item_not_hashable bool_type "
                + "bool_parsing int_type int_parsing int_parsing_size int_from_float float_type float_parsing "
                + "bytes_type bytes_too_short bytes_too_long bytes_invalid_encoding value_error assertion_error "
                + "literal_error missing_sentinel_error date_type date_parsing date_from_datetime_parsing "
                + "date_from_datetime_inexact date_past date_future time_type time_parsing datetime_type "
                + "datetime_parsing datetime_object_invalid datetime_from_date_parsing datetime_past "
                + "datetime_future timezone_naive timezone_aware timezone_offset time_delta_type time_delta_parsing "
                + "frozen_set_type is_instance_of is_subclass_of callable_type union_tag_invalid union_tag_not_found "
                + "arguments_type missing_argument unexpected_keyword_argument missing_keyword_only_argument "
                + "unexpected_positional_argument missing_positional_only_argument multiple_argument_values "
                + "url_type url_parsing url_syntax_violation url_too_long url_scheme uuid_type uuid_parsing "
                + "uuid_version decimal_type decimal_parsing decimal_max_digits decimal_max_places "
                + "decimal_whole_digits complex_type complex_str_parsing").split(" "));

        public ValidationFailure {
            if (stage == null || !stage.matches("(?:MAP|REVIEW|REDUCE)(?:-[0-9]{3})?")
                    || attempt < 1 || attempt > 2 || errorType == null || !TYPES.contains(errorType)
                    || errorCount < 0 || errorCount > 1_000_000 || errorKinds == null
                    || errorKinds.size() > 5 || errorKinds.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("Invalid validation failure diagnostics");
            }
            if (issues == null || issues.size() > 8 || issues.stream().anyMatch(value -> value == null)
                    || issues.stream().anyMatch(issue -> !issue.matchesStage(stage))) {
                throw new IllegalArgumentException("Invalid validation issues");
            }
            errorKinds = errorKinds.stream().filter(KINDS::contains).distinct().sorted().toList();
            issues = List.copyOf(issues);
        }
    }

    /** Closed field paths and numeric references only; no model text or dynamic locations. */
    public record ValidationIssue(String audience, String field, String errorKind, List<String> claimIds) {
        private static final Set<String> AUDIENCES = Set.of(
                "CHIP_MAKER", "EQUIPMENT_MAKER", "MARKET_INVESTOR", "IT_INFRA");
        private static final Set<String> LENGTH_KINDS = Set.of(
                "string_too_long", "string_too_short", "too_long", "too_short");
        private static final String REDUCE_FIELD = "(?:headline|overview|implications|watchItems|"
                + "overview\\[[0-2]\\]|(?:implications|watchItems)\\[[0-4]\\]|"
                + "overview\\[[0-2]\\]\\.(?:text|assumption|basisClaimIds)|"
                + "implications\\[[0-4]\\]\\.(?:text|mechanism|assumption|falsifiedBy|basisClaimIds)|"
                + "watchItems\\[[0-4]\\]\\.(?:topic|indicator|trigger|basisClaimIds))";
        private static final String MAP_FIELD = "assessments\\[[1-9][0-9]{0,18}\\]"
                + "(?:\\.(?:reason|basisClaimIds|axes\\.(?:directness|impact|urgency|novelty)|"
                + "decision\\.connection\\.(?:condition|relation|work)|"
                + "decision\\.effect\\.impactScope|decision\\.timing\\.urgencyState|"
                + "decision\\.(?:connection|effect|timing)\\.basis(?:\\.(?:claimId|sourceSpanId))?))?";

        private boolean matchesStage(String stage) {
            return field.matches(stage.matches("(?:MAP|REVIEW)(?:-[0-9]{3})?") ? MAP_FIELD : REDUCE_FIELD);
        }

        public ValidationIssue {
            if (audience == null || !AUDIENCES.contains(audience)
                    || field == null || !(field.matches(REDUCE_FIELD) || field.matches(MAP_FIELD))
                    || errorKind == null || !(LENGTH_KINDS.contains(errorKind)
                        || errorKind.startsWith("report_") && ValidationFailure.KINDS.contains(errorKind))
                    || claimIds == null || claimIds.size() > 8
                    || claimIds.stream().anyMatch(value -> value == null
                        || !value.matches("[1-9][0-9]{0,18}:(?:0|[1-9][0-9]{0,18})"))) {
                throw new IllegalArgumentException("Invalid validation issue");
            }
            claimIds = List.copyOf(claimIds);
        }
    }

    public record ExecutionMetadata(String provider,
                                    String model,
                                    String promptVersion,
                                    String source,
                                    String usageCompleteness) {
        public ExecutionMetadata(String provider, String model, String promptVersion, String source) {
            this(provider, model, promptVersion, source, "UNKNOWN");
        }
    }
}
