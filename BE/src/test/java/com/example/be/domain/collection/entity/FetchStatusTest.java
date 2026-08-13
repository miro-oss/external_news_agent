package com.example.be.domain.collection.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 저장값은 다섯 가지인데 명세의 응답값은 OK/BLOCKED 둘뿐이다. 매핑을 고정해 두지 않으면
 * 후속 기사 API가 내부 값을 그대로 흘려보낸다.
 */
class FetchStatusTest {

    @Test
    void mapsOnlyFullTextToOk() {
        assertEquals(FetchStatus.API_OK, FetchStatus.FULLTEXT.toApiValue());
    }

    @Test
    void mapsEveryOtherStateToBlocked() {
        assertEquals(FetchStatus.API_BLOCKED, FetchStatus.METADATA_ONLY.toApiValue());
        assertEquals(FetchStatus.API_BLOCKED, FetchStatus.FULLTEXT_BLOCKED.toApiValue());
        assertEquals(FetchStatus.API_BLOCKED, FetchStatus.ROBOTS_DISALLOWED.toApiValue());
        assertEquals(FetchStatus.API_BLOCKED, FetchStatus.FETCH_FAILED.toApiValue());
    }

    /**
     * 상태가 늘어나도 명세에 없는 값이 새어 나가면 안 된다.
     */
    @Test
    void neverLeaksAnInternalValue() {
        assertTrue(Arrays.stream(FetchStatus.values())
                .map(FetchStatus::toApiValue)
                .allMatch(value -> FetchStatus.API_OK.equals(value) || FetchStatus.API_BLOCKED.equals(value)));
    }
}
