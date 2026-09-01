package com.example.be.global.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 정수 목록을 JSON 배열로 저장한다. */
@Converter
public class IntegerListJsonConverter implements AttributeConverter<List<Integer>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EMPTY_ARRAY = "[]";

    @Override
    public String convertToDatabaseColumn(List<Integer> attribute) {
        return attribute == null || attribute.isEmpty()
                ? EMPTY_ARRAY
                : OBJECT_MAPPER.writeValueAsString(attribute);
    }

    @Override
    public List<Integer> convertToEntityAttribute(String dbData) {
        if (!StringUtils.hasText(dbData)) {
            return List.of();
        }
        List<?> values = OBJECT_MAPPER.readValue(dbData, List.class);
        return values.stream().map(value -> ((Number) value).intValue()).toList();
    }
}
