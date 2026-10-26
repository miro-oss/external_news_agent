package com.example.be.domain.reports.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;

@Converter
public class LocalDateListConverter implements AttributeConverter<List<LocalDate>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() { };
    public String convertToDatabaseColumn(List<LocalDate> value) {
        return MAPPER.writeValueAsString(value == null ? List.of() : value.stream().map(LocalDate::toString).toList());
    }
    public List<LocalDate> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return List.of();
        return MAPPER.readValue(value, TYPE).stream().map(LocalDate::parse).toList();
    }
}
