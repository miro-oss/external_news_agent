package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.ArticleBody;
import com.example.be.domain.collection.repository.ArticleBodyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

import java.io.Reader;
import java.io.StringWriter;
import java.sql.PreparedStatement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ArticleBodyStorageTest {

    private final ArticleBodyRepository bodyRepository = mock(ArticleBodyRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ArticleBodyStorage storage = new ArticleBodyStorage(bodyRepository, jdbcTemplate);

    @Test
    void missingBodyDoesNotCreateAStorageRow() {
        assertNull(storage.intern(null));
        verifyNoInteractions(bodyRepository, jdbcTemplate);
    }

    @Test
    void reusesAnExistingBodyWithoutInsertingItAgain() {
        ArticleBody existing = ArticleBody.of("동일한 본문");
        when(bodyRepository.findById(existing.getBodyHash())).thenReturn(Optional.of(existing));

        assertSame(existing, storage.intern("동일한 본문"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void insertsALongBodyAsAClobStreamAndReturnsTheManagedBody() throws Exception {
        String text = "한글😀 본문\n".repeat(10_000);
        ArticleBody managed = ArticleBody.of(text);
        when(bodyRepository.findById(managed.getBodyHash()))
                .thenReturn(Optional.empty(), Optional.of(managed));

        assertSame(managed, storage.intern(text));

        ArgumentCaptor<PreparedStatementSetter> setter = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbcTemplate).update(anyString(), setter.capture());
        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement);
        verify(statement).setString(1, managed.getBodyHash());
        ArgumentCaptor<Reader> reader = ArgumentCaptor.forClass(Reader.class);
        verify(statement).setClob(eq(2), reader.capture(), eq((long) text.length()));
        StringWriter streamed = new StringWriter();
        reader.getValue().transferTo(streamed);
        assertEquals(text, streamed.toString());
        verify(bodyRepository, times(2)).findById(managed.getBodyHash());
    }

    @Test
    void insertsAnEmptyBodyUsingAnEmptyClobWithoutBindingAZeroLengthStream() throws Exception {
        ArticleBody managed = ArticleBody.of("");
        when(bodyRepository.findById(managed.getBodyHash()))
                .thenReturn(Optional.empty(), Optional.of(managed));

        assertSame(managed, storage.intern(""));

        ArgumentCaptor<PreparedStatementSetter> setter = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), setter.capture());
        assertTrue(sql.getValue().contains("EMPTY_CLOB()"));
        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement);
        verify(statement).setString(1, managed.getBodyHash());
        verifyNoMoreInteractions(statement);
    }

    @Test
    void refusesToShareDifferentTextEvenWhenItsHashLookupMatches() {
        String input = "이번 수집 원문";
        ArticleBody existing = ArticleBody.of("다른 원문");
        when(bodyRepository.findById(ArticleBody.of(input).getBodyHash())).thenReturn(Optional.of(existing));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> storage.intern(input));

        assertFalse(exception.getMessage().contains(input));
        assertFalse(exception.getMessage().contains(existing.getBody()));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void alsoValidatesTheWinnerAfterAConcurrentInsert() {
        String input = "동시에 저장한 원문";
        when(bodyRepository.findById(ArticleBody.of(input).getBodyHash()))
                .thenReturn(Optional.empty(), Optional.of(ArticleBody.of("다른 원문")));

        assertThrows(IllegalStateException.class, () -> storage.intern(input));

        verify(jdbcTemplate).update(anyString(), any(PreparedStatementSetter.class));
    }
}
