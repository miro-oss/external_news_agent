package com.example.be.domain.reports.converter;

import com.example.be.domain.reports.entity.WeeklyReportInput;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;

@Converter
public class WeeklyReportInputConverter implements AttributeConverter<WeeklyReportInput, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public String convertToDatabaseColumn(WeeklyReportInput value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }
    public WeeklyReportInput convertToEntityAttribute(String value) {
        return value == null || value.isBlank() ? null : MAPPER.readValue(value, WeeklyReportInput.class);
    }
}
