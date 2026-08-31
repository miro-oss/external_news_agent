package com.example.be.global.database;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OracleInClauseTest {

    @Test
    void splitsValuesBelowOracleInClauseLimitWithoutChangingOrder() {
        List<Integer> values = IntStream.rangeClosed(1, 2_001).boxed().toList();

        List<List<Integer>> batches = OracleInClause.batches(values);

        assertEquals(List.of(900, 900, 201), batches.stream().map(List::size).toList());
        assertEquals(values, batches.stream().flatMap(List::stream).toList());
    }
}
