package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TitleTokenizerFixtureTest {

    @Test
    void matchesSharedPythonTokenContract() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/golden/tokenization.v1.json")) {
            Fixture fixture = new ObjectMapper().readValue(input, Fixture.class);
            fixture.cases().forEach(value -> assertEquals(
                    Set.copyOf(value.tokens()),
                    TitleTokenizer.tokens(value.text()),
                    value.text()));
        }
    }

    @Test
    void stripsBreakingPrefixAndPublisherSuffixBeforeTokenizing() {
        assertEquals(
                TitleTokenizer.tokens("삼성전자 HBM4 양산 발표"),
                TitleTokenizer.tokens("[속보] 삼성전자 HBM4 양산 발표 - 연합뉴스"));
    }

    private record Fixture(String version, List<FixtureCase> cases) {
    }

    private record FixtureCase(String text, List<String> tokens) {
    }
}
