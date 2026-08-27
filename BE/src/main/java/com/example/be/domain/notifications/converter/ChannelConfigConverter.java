package com.example.be.domain.notifications.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Converter
public class ChannelConfigConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        return OBJECT_MAPPER.writeValueAsString(attribute == null ? Map.of() : attribute);
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        return dbData == null || dbData.isBlank()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(OBJECT_MAPPER.readValue(dbData, TYPE));
    }
}
