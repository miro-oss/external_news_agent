package com.example.be.domain.topics.converter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicKeywordSelectionListConverterTest {

    private final TopicKeywordSelectionListConverter converter = new TopicKeywordSelectionListConverter();

    @Test
    void preservesNullForPendingProposalsAndLegacyApprovals() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void roundTripsSelectedIndexesAndKeepsEmptyDistinctFromNull() {
        for (var selection : List.of(List.<Integer>of(), List.of(0, 2, 4))) {
            assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(selection)))
                    .isEqualTo(selection);
        }
        assertThat(converter.convertToDatabaseColumn(List.of(0, 2))).isEqualTo("[0,2]");
    }
}
