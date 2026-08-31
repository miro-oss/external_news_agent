package com.example.be.global.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Long ID 목록을 Oracle JSON CLOB 배열로 저장한다. */
@Converter
public class LongListJsonConverter implements AttributeConverter<List<Long>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EMPTY_ARRAY = "[]";

    @Override
    public String convertToDatabaseColumn(List<Long> attribute) {
        return attribute == null || attribute.isEmpty()
                ? EMPTY_ARRAY
                : OBJECT_MAPPER.writeValueAsString(attribute);
    }

    @Override
    public List<Long> convertToEntityAttribute(String dbData) {
        if (!StringUtils.hasText(dbData)) {
            return List.of();
        }
        List<?> values = OBJECT_MAPPER.readValue(dbData, List.class);
        return values.stream()
                .map(value -> value instanceof Number number
                        ? number.longValue()
                        : Long.parseLong(String.valueOf(value)))
                .toList();
    }
}
