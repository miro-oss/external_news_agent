package com.example.be.domain.reports.converter;

import com.example.be.domain.reports.entity.ReportContent;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;

@Converter
public class ReportContentConverter implements AttributeConverter<ReportContent, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public String convertToDatabaseColumn(ReportContent value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }
    public ReportContent convertToEntityAttribute(String value) {
        return value == null || value.isBlank() ? null : MAPPER.readValue(value, ReportContent.class);
    }
}
