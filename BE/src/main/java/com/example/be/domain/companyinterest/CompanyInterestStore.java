package com.example.be.domain.companyinterest;

import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
import com.example.be.domain.feedback.model.FeedbackModels.Item;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.example.be.domain.companyinterest.CompanyInterestModels.*;

@Repository
@RequiredArgsConstructor
public class CompanyInterestStore {
    private static final String SELECT = """
            SELECT c.*,f.report_id AS source_report_id,f.user_comment,f.diagnosis,f.input_json
            FROM news_company_interests c JOIN news_feedback f ON f.id=c.feedback_id
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public List<StoredCriterion> all() {
        return jdbc.query(SELECT + " ORDER BY c.created_at DESC,c.id DESC", this::criterion).stream()
                .filter(this::hasEvent).toList();
    }

    public Optional<StoredCriterion> byId(long id, boolean lock) {
        return jdbc.query(SELECT + " WHERE c.id=?" + (lock ? " FOR UPDATE OF c.version" : ""),
                this::criterion, id).stream().filter(this::hasEvent).findFirst();
    }

    /** Review completion and draft creation share one transaction; review retries cannot reset a decision. */
    public boolean propose(Feedback feedback, JsonNode result, LocalDateTime now) {
        if (feedback.recipientId() != null || feedback.eventKey() == null || feedback.input() == null
                || feedback.input().event() == null || !"PREFERENCE".equals(result.path("verdict").asString())
                || result.path("meta").path("mock").asBoolean()
                || result.path("meta").path("truncated").asBoolean()) return false;
        var candidate = result.path("proposedCompanyCriterion");
        String instruction = candidate.path("instruction").asString("").strip();
        String reason = candidate.path("reason").asString("").strip();
        if (instruction.isBlank() || instruction.length() > 500 || reason.isBlank() || reason.length() > 500) {
            instruction = feedback.comment().strip();
            instruction = instruction.substring(0, Math.min(instruction.length(), 500));
            reason = "원래 관심 의견을 초안으로 옮겼습니다. 적용할 기준과 주제를 확인해 주세요.";
        }
        var topics = topics(feedback.input());
        Long topicId = topics.size() == 1 ? topics.getFirst().id() : null;
        try {
            jdbc.update("""
                    INSERT INTO news_company_interests(feedback_id,topic_id,instruction,reason,status,version,created_at,updated_at)
                    VALUES(?,?,?,?,'DRAFT',1,?,?)
                    """, feedback.id(), topicId, instruction, reason, now, now);
        } catch (DuplicateKeyException existing) {
            // A duplicate review delivery must retain the user's edited/activated/revoked criterion.
        }
        return true;
    }

    public boolean lockTopic(long topicId) {
        return !jdbc.queryForList("SELECT id FROM news_topics WHERE id=? FOR UPDATE", Long.class, topicId).isEmpty();
    }

    public int activeCount(long topicId, long excludingId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM news_company_interests WHERE topic_id=? AND status='ACTIVE' AND id<>?",
                Integer.class, topicId, excludingId);
    }

    public boolean update(long id, long version, String instruction, Long topicId, Status status, LocalDateTime now) {
        return jdbc.update("""
                UPDATE news_company_interests SET instruction=?,topic_id=?,status=?,version=version+1,
                    updated_at=?,activated_at=CASE ? WHEN 'ACTIVE' THEN ? WHEN 'REVOKED' THEN activated_at END
                WHERE id=? AND version=?
                """, instruction, topicId, status.name(), now, status.name(), now, id, version) == 1;
    }

    public List<ActiveCriterion> active(long topicId) { return activeForTopics(Set.of(topicId)); }

    public List<ActiveCriterion> activeForTopics(Set<Long> topicIds) {
        return OracleInClause.batches(topicIds).stream().flatMap(ids -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            return jdbc.query(SELECT + " WHERE c.status='ACTIVE' AND c.topic_id IN (" + placeholders + ") ORDER BY c.id",
                    this::criterion, ids.toArray()).stream();
        }).filter(this::hasEvent).map(criterion -> new ActiveCriterion(criterion.id(), criterion.topicId(),
                topics(criterion.input()).stream().filter(t -> t.id() == criterion.topicId()).map(Topic::name).findFirst().orElse(""),
                criterion.instruction(), criterion.version())).toList();
    }

    /** Store the frozen evaluation once alongside the report's frozen selection. Pending reports stay invisible. */
    public void recordApplication(long reportId, ActiveCriterion criterion, int evaluatedCount,
                                  int suppressedCount, LocalDateTime appliedAt) {
        if (evaluatedCount < 1 || suppressedCount < 0 || suppressedCount > evaluatedCount)
            throw new IllegalArgumentException("Invalid company interest application counts");
        try {
            jdbc.update("""
                    INSERT INTO news_company_interest_apps(report_id,criterion_id,criterion_version,topic_id_snapshot,
                        instruction_snapshot,evaluated_count,suppressed_count,evaluated_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, reportId, criterion.id(), criterion.version(), criterion.topicId(), criterion.instruction(),
                    evaluatedCount, suppressedCount, appliedAt);
        } catch (DuplicateKeyException existing) {
            // A recovered report keeps its original immutable selection/evaluation snapshot.
        }
    }

    public Optional<Application> lastApplication(long criterionId, long version) {
        return jdbc.query("""
                SELECT a.report_id,r.report_scope,r.generated_at,a.evaluated_count,a.suppressed_count
                FROM news_company_interest_apps a JOIN news_reports r ON r.id=a.report_id
                WHERE a.criterion_id=? AND a.criterion_version=?
                  AND r.report_status IN ('GENERATED','FALLBACK') AND r.deleted_at IS NULL
                ORDER BY r.generated_at DESC,a.report_id DESC FETCH FIRST 1 ROW ONLY
                """, (rs, row) -> new Application(rs.getLong("report_id"), rs.getString("report_scope"),
                time(rs, "generated_at").atZone(ApiTimeZone.ZONE).toOffsetDateTime(),
                rs.getInt("evaluated_count"), rs.getInt("suppressed_count")), criterionId, version).stream().findFirst();
    }

    private StoredCriterion criterion(ResultSet rs, int row) throws SQLException {
        return new StoredCriterion(rs.getLong("id"), rs.getLong("feedback_id"), rs.getLong("source_report_id"),
                rs.getString("user_comment"), rs.getString("diagnosis"), readInput(rs.getString("input_json")),
                rs.getObject("topic_id", Long.class), rs.getString("instruction"), rs.getString("reason"),
                Status.valueOf(rs.getString("status")), rs.getLong("version"), time(rs, "created_at"),
                time(rs, "updated_at"), time(rs, "activated_at"));
    }

    private Item readInput(String value) {
        try { return json.readValue(value, Item.class); }
        catch (RuntimeException incompatibleLegacyInput) { return null; }
    }

    private boolean hasEvent(StoredCriterion criterion) {
        return criterion.input() != null && criterion.input().event() != null;
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
