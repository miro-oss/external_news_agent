package com.example.be.global.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 문자열 목록을 JSON 배열 문자열로 저장한다. 대상 컬럼에는 IS JSON 제약이 걸려 있다.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EMPTY_ARRAY = "[]";

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        return attribute == null || attribute.isEmpty()
                ? EMPTY_ARRAY
                : OBJECT_MAPPER.writeValueAsString(attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (!StringUtils.hasText(dbData)) {
            return List.of();
        }
        List<?> values = OBJECT_MAPPER.readValue(dbData, List.class);
        return values.stream().map(String::valueOf).toList();
    }
}
