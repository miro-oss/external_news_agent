package com.example.be.domain.analysis.converter;

import jakarta.persistence.AttributeConverter;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** JSON 배열 CLOB을 값 객체 목록으로 변환하는 공통 JPA 컨버터. */
public abstract class JsonListConverter<T> implements AttributeConverter<List<T>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TypeReference<List<T>> type;

    protected JsonListConverter(TypeReference<List<T>> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(List<T> attribute) {
        return OBJECT_MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<T> convertToEntityAttribute(String dbData) {
        return StringUtils.hasText(dbData) ? OBJECT_MAPPER.readValue(dbData, type) : List.of();
    }
}
