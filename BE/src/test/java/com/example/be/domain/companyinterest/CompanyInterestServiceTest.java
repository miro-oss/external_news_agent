package com.example.be.domain.companyinterest;

import com.example.be.domain.feedback.model.FeedbackModels;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.be.domain.companyinterest.CompanyInterestModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanyInterestServiceTest {
    private final CompanyInterestStore store = mock(CompanyInterestStore.class);
    private final CompanyInterestService service = new CompanyInterestService(store);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 10, 0);

    @Test void listsImmutableSourceAndCurrentVersionApplicationWithoutWrites() {
        var source = criterion(Status.ACTIVE, 3, 2L);
        when(store.all()).thenReturn(List.of(source));
        var result = service.list().criteria().getFirst();
        assertEquals("원본 의견 전체", result.comment());
        assertEquals("수학 성과", result.eventTitle());
        assertEquals(List.of(new Topic(2, "AI"), new Topic(3, "산업")), result.topics());
        assertEquals("2026-10-12T10:00+09:00", result.createdAt().toString());
        assertNull(result.lastApplication());
        verify(store).all(); verify(store).lastApplication(1, 3); verifyNoMoreInteractions(store);
    }

    @Test void ambiguousDraftCanBeEditedBeforeChoosingTopicButCannotActivate() {
        when(store.byId(1, true)).thenReturn(Optional.of(criterion(Status.DRAFT, 1, null)));
        when(store.update(eq(1L), eq(1L), eq("수학 연구만 다룬 소식은 제외"), isNull(), eq(Status.DRAFT), any()))
                .thenReturn(true);
        when(store.byId(1, false)).thenReturn(Optional.of(criterion(Status.DRAFT, 2, null)));
        assertEquals(2, service.update(1, new Update(1L, "  수학 연구만 다룬 소식은 제외  ", null, "DRAFT")).version());
        assertCode(GeneralErrorCode.BAD_REQUEST, () -> service.update(1, new Update(2L, "기준", null, "ACTIVE")));
        verify(store, never()).lockTopic(anyLong());
    }

    @Test void onlyFrozenTopicsAreAllowedAndStaleVersionNeverOverwrites() {
        when(store.byId(1, true)).thenReturn(Optional.of(criterion(Status.DRAFT, 2, null)));
        assertCode(GeneralErrorCode.CONFLICT, () -> service.update(1, new Update(1L, "기준", 2L, "ACTIVE")));
        assertCode(GeneralErrorCode.BAD_REQUEST, () -> service.update(1, new Update(2L, "기준", 99L, "ACTIVE")));
        verify(store, never()).update(anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test void activeLimitCheckIsSerializedAndExcludesTheUpdatedCriterion() {
        when(store.byId(1, true)).thenReturn(Optional.of(criterion(Status.DRAFT, 1, null)));
        when(store.lockTopic(2)).thenReturn(true);
        when(store.activeCount(2, 1)).thenReturn(20);
        assertCode(GeneralErrorCode.CONFLICT, () -> service.update(1, new Update(1L, "기준", 2L, "ACTIVE")));
        var order = inOrder(store);
        order.verify(store).byId(1, true); order.verify(store).lockTopic(2); order.verify(store).activeCount(2, 1);
        verify(store, never()).update(anyLong(), anyLong(), anyString(), any(), any(), any());
    }

    @Test void activationReturnsWaitingUntilTheNewVersionHasAnApplication() {
        when(store.byId(1, true)).thenReturn(Optional.of(criterion(Status.DRAFT, 1, null)));
        when(store.lockTopic(2)).thenReturn(true);
        when(store.update(eq(1L), eq(1L), eq("기준"), eq(2L), eq(Status.ACTIVE), any())).thenReturn(true);
        when(store.byId(1, false)).thenReturn(Optional.of(criterion(Status.ACTIVE, 2, 2L)));
        var result = service.update(1, new Update(1L, "기준", 2L, "ACTIVE"));
        assertEquals(Status.ACTIVE, result.status()); assertNotNull(result.activatedAt()); assertNull(result.lastApplication());
        verify(store).lastApplication(1, 2);
    }

    @Test void identicalCurrentVersionIsIdempotentWithoutErasingApplication() {
        when(store.byId(1, true)).thenReturn(Optional.of(criterion(Status.ACTIVE, 2, 2L)));
        var application = new Application(8, "DAILY", NOW.atOffset(java.time.ZoneOffset.ofHours(9)), 8, 0);
        when(store.lastApplication(1, 2)).thenReturn(Optional.of(application));
        var result = service.update(1, new Update(2L, "  기준  ", 2L, "ACTIVE"));
        assertEquals(2, result.version()); assertEquals(application, result.lastApplication());
        verify(store, never()).update(anyLong(), anyLong(), anyString(), any(), any(), any());
        verify(store, never()).lockTopic(anyLong());
    }

    private static StoredCriterion criterion(Status status, long version, Long topicId) {
        var topics = List.of(new FeedbackModels.Topic(2, "AI", List.of(), List.of()),
                new FeedbackModels.Topic(3, "산업", List.of(), List.of()));
        var event = new FeedbackModels.EventContext("a".repeat(64), 0, "수학 성과", "요약", "의미", List.of(1L), topics, List.of(), null);
        var item = new FeedbackModels.Item(null, null, null, List.of(), null, null, null, null, "v1", "model", null, event);
        return new StoredCriterion(1, 10, 17, "원본 의견 전체", "진단", item, topicId, "기준", "사용자 관심 표현", status,
                version, NOW, NOW, status == Status.ACTIVE ? NOW : null);
    }
    private static void assertCode(GeneralErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(GeneralException.class, action).getCode());
    }
}
