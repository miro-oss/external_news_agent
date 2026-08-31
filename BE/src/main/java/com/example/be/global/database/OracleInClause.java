package com.example.be.global.database;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Oracle의 IN 절 1,000개 제한을 넘지 않도록 조회 대상을 안전한 크기로 나눈다. */
public final class OracleInClause {

    public static final int BATCH_SIZE = 900;

    private OracleInClause() {
    }

    public static <T> List<List<T>> batches(Collection<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> ordered = List.copyOf(values);
        List<List<T>> result = new ArrayList<>((ordered.size() + BATCH_SIZE - 1) / BATCH_SIZE);
        for (int from = 0; from < ordered.size(); from += BATCH_SIZE) {
            result.add(ordered.subList(from, Math.min(from + BATCH_SIZE, ordered.size())));
        }
        return List.copyOf(result);
    }
}
