package com.example.be.domain.feedback.service;

import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.feedback.model.FeedbackModels.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackLearningServiceTest {
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 10, 7, 12, 0);
    private static final CollectionTopicSnapshot TOPIC = scope(1, "HBM", List.of("HBM"));
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper json = new ObjectMapper();
    private final FeedbackLearningService service = new FeedbackLearningService(jdbc, json);
    private final AtomicBoolean closed = new AtomicBoolean();

    @Test void existingCompletedReviewIsRestoredWithVerifiedEvidenceOnTheNextRequest() throws Exception {
        database(List.of(row(23, item(TOPIC), review(false, false, "CONFIRMED_ERROR", "양산을 검토 중이다."))));

        var examples = service.forSnapshots(List.of(TOPIC), AS_OF, Set.of(Category.SUMMARY_ERROR));

        assertEquals(1, examples.size());
        var example = examples.getFirst();
        assertEquals(23, example.feedbackId());
        assertEquals(1, example.topicId());
        assertEquals("SUMMARY_ERROR", example.category());
        assertEquals("양산을 검토 중이다.", example.evidence().getFirst().quote());
        assertEquals("검토를 완료로 표현하지 않는다.", example.diagnosis());
        assertTrue(closed.get(), "The JDBC stream must be closed after learning input is frozen");

        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForStream(sql.capture(), any(RowMapper.class), args.capture());
        assertTrue(sql.getValue().contains("f.status='COMPLETED'"));
        assertTrue(sql.getValue().contains("f.verdict='CONFIRMED_ERROR'"));
        assertTrue(sql.getValue().contains("f.recipient_id IS NULL"));
        assertTrue(sql.getValue().contains("report.deleted_at IS NULL"));
        assertTrue(sql.getValue().contains("f.finished_at<=?"));
        assertTrue(sql.getValue().contains("JSON_TABLE"));
        assertArrayEquals(new Object[]{"SUMMARY_ERROR", Timestamp.valueOf(AS_OF), 1L}, args.getValue());
    }

    @Test void unrelatedTopicAndChangedCollectionConditionsAreNotLearned() throws Exception {
        database(List.of(row(24, item(scope(2, "배터리", List.of("배터리"))), validReview()),
                row(23, item(scope(1, "HBM", List.of("구형 제품"))), validReview())));
        assertTrue(service.forSnapshots(List.of(TOPIC), AS_OF, Set.of(Category.SUMMARY_ERROR)).isEmpty());
    }

    @Test void keywordOrderDoesNotDisableAValidHistoricalScope() throws Exception {
        var prior = scope(1, "HBM", List.of("HBM", "반도체"));
        var current = scope(1, "HBM", List.of("반도체", "HBM"));
        database(List.of(row(23, item(prior), validReview())));
        assertEquals(1, service.forSnapshots(List.of(current), AS_OF, Set.of(Category.SUMMARY_ERROR)).size());
    }

    @Test void mockTruncatedUnconfirmedAndFabricatedEvidenceCannotBecomeLearningExamples() throws Exception {
        database(List.of(row(25, item(TOPIC), review(true, false, "CONFIRMED_ERROR", "양산을 검토 중이다.")),
                row(24, item(TOPIC), review(false, true, "CONFIRMED_ERROR", "양산을 검토 중이다.")),
                row(23, item(TOPIC), review(false, false, "NOT_CONFIRMED", "양산을 검토 중이다.")),
                row(22, item(TOPIC), review(false, false, "INSUFFICIENT_EVIDENCE", "양산을 검토 중이다.")),
                row(21, item(TOPIC), review(false, false, "CONFIRMED_ERROR", "이미 양산이 완료됐다."))));
        assertTrue(service.forSnapshots(List.of(TOPIC), AS_OF, Set.of(Category.SUMMARY_ERROR)).isEmpty());
    }

    @Test void incompleteHistoricalScopeCannotBeReconstructedFromCurrentSettings() throws Exception {
        var item = item(TOPIC);
        var source = item.event().sources().getFirst();
        var incomplete = new EventSource(source.findingId(), source.runId(), source.article(), source.topics(),
                source.collectionTopics(), null, null, null, AS_OF.minusDays(1), "요약", false);
        var event = item.event();
        var input = new Item(null, null, null, item.articles(), null, null, null, null, null, null, null,
                new EventContext(event.key(), 0, event.title(), event.summary(), null, event.sourceFindingIds(),
                        event.topics(), List.of(incomplete), null));
        database(List.of(row(23, input, validReview())));
        assertTrue(service.forSnapshots(List.of(TOPIC), AS_OF, Set.of(Category.SUMMARY_ERROR)).isEmpty());
    }

    @Test void multiTopicReviewRetainsOnlyCitationsBelongingToEachRequestedScope() throws Exception {
        var second = scope(2, "배터리", List.of("배터리"));
        var input = item(TOPIC, second);
        var review = Map.of("verdict", "CONFIRMED_ERROR", "diagnosis", "검토를 완료로 표현하지 않는다.",
                "evidence", List.of(Map.of("articleId", 101, "quote", "양산을 검토 중이다."),
                        Map.of("articleId", 102, "quote", "납품을 검토 중이다.")),
                "meta", Map.of("provider", "openai", "mock", false, "truncated", false));
        database(List.of(row(23, input, json.writeValueAsString(review))));

        var examples = service.forSnapshots(List.of(TOPIC, second), AS_OF, Set.of(Category.SUMMARY_ERROR));

        assertEquals(List.of(1L, 2L), examples.stream().map(value -> value.topicId()).toList());
        assertEquals(List.of(101L), examples.get(0).evidence().stream().map(value -> value.articleId()).toList());
        assertEquals(List.of(102L), examples.get(1).evidence().stream().map(value -> value.articleId()).toList());
    }

    @Test void oldSettingsCannotCrowdApplicableFeedbackOutOfTheBoundedLearningInput() throws Exception {
        List<ResultSet> rows = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            rows.add(row(100 - index, item(scope(1, "HBM", List.of("구형"))), validReview()));
        }
        for (int index = 0; index < 7; index++) rows.add(row(80 - index, item(TOPIC), validReview()));
        database(rows);
        var examples = service.forSnapshots(List.of(TOPIC), AS_OF, Set.of(Category.SUMMARY_ERROR));
        assertEquals(List.of(80L, 79L, 78L, 77L, 76L), examples.stream().map(value -> value.feedbackId()).toList());
        assertTrue(closed.get());
    }

    @Test void personalPreferenceAndAmbiguousTopicSnapshotsDoNotQuerySharedLearning() {
        assertTrue(service.forSnapshots(List.of(TOPIC), AS_OF, Set.of(Category.PREFERENCE)).isEmpty());
        assertTrue(service.forSnapshots(List.of(TOPIC, scope(1, "새 범위", List.of("HBM"))),
                AS_OF, Set.of(Category.SUMMARY_ERROR)).isEmpty());
        verifyNoInteractions(jdbc);
    }

    @Test void corruptedLegacyJsonIsSkippedWithoutLosingTheNextValidReview() throws Exception {
        var broken = row(24, item(TOPIC), "{broken");
        database(List.of(broken, row(23, item(TOPIC), validReview())));
        assertEquals(23, service.forSnapshots(List.of(TOPIC), AS_OF,
                Set.of(Category.SUMMARY_ERROR)).getFirst().feedbackId());
    }

    private ResultSet row(long id, Item input, String review) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("category")).thenReturn("SUMMARY_ERROR");
        when(row.getString("input_json")).thenReturn(json.writeValueAsString(input));
        when(row.getString("review_json")).thenReturn(review);
        return row;
    }

    private void database(List<ResultSet> rows) {
        when(jdbc.queryForStream(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            RowMapper<?> mapper = call.getArgument(1);
            return rows.stream().map(row -> {
                try { return mapper.mapRow(row, 0); }
                catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
            }).onClose(() -> closed.set(true));
        });
    }

    private String validReview() { return review(false, false, "CONFIRMED_ERROR", "양산을 검토 중이다."); }
    private String review(boolean mock, boolean truncated, String verdict, String quote) {
        return json.writeValueAsString(Map.of("verdict", verdict, "diagnosis", "검토를 완료로 표현하지 않는다.",
                "evidence", List.of(Map.of("articleId", 101, "quote", quote)),
                "meta", Map.of("provider", mock ? "mock" : "openai", "mock", mock, "truncated", truncated)));
    }
    private static CollectionTopicSnapshot scope(long id, String name, List<String> keywords) {
        return new CollectionTopicSnapshot(id, name, "검색어", keywords, List.of(), List.of(), 10, 1440);
    }
    private static Item item(CollectionTopicSnapshot... snapshots) {
        List<EventSource> sources = new ArrayList<>();
        List<Article> articles = new ArrayList<>();
        List<Topic> topics = new ArrayList<>();
        for (int index = 0; index < snapshots.length; index++) {
            var snapshot = snapshots[index];
            var topic = new Topic(snapshot.topicId(), snapshot.topicName(), snapshot.requiredKeywords(), List.of());
            var article = new Article(101 + index, "기사 " + index,
                    index == 0 ? "양산을 검토 중이다." : "납품을 검토 중이다.", "https://example.com/" + index);
            topics.add(topic);
            articles.add(article);
            sources.add(new EventSource(201 + index, 3L, article, List.of(topic), List.of(snapshot),
                    "hash", "prompt", "model", AS_OF.minusDays(1), "잘못된 요약"));
        }
        return new Item(null, null, null, articles, null, null, null, null, null, null, null,
                new EventContext("a".repeat(64), 0, "이벤트", "양산이 완료됐다.", null,
                        sources.stream().map(EventSource::findingId).toList(), topics, sources, null));
    }
}
