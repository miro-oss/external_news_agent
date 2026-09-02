package com.example.be.global.converter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegerListJsonConverterTest {

    private final IntegerListJsonConverter converter = new IntegerListJsonConverter();

    @Test
    void readsNumericAndStringValues() {
        assertEquals(List.of(1, 2), converter.convertToEntityAttribute("[1,\"2\"]"));
    }

    @Test
    void rejectsNullElementsWithClearError() {
        assertThrows(IllegalArgumentException.class,
                () -> converter.convertToEntityAttribute("[null]"));
    }
}
