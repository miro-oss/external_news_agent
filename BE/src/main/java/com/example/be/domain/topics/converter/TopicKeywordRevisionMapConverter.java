package com.example.be.domain.topics.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Converter
public class TopicKeywordRevisionMapConverter implements AttributeConverter<Map<String, Long>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Long>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Long> value) {
        return MAPPER.writeValueAsString(value == null ? Map.of() : value);
    }

    @Override
    public Map<String, Long> convertToEntityAttribute(String value) {
        return StringUtils.hasText(value) ? MAPPER.readValue(value, TYPE) : Map.of();
    }
}
