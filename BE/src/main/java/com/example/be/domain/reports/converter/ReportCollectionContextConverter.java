package com.example.be.domain.reports.converter;

import com.example.be.domain.reports.entity.ReportCollectionContext;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;

@Converter
public class ReportCollectionContextConverter implements AttributeConverter<List<ReportCollectionContext>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public String convertToDatabaseColumn(List<ReportCollectionContext> value) {
        return MAPPER.writeValueAsString(value == null ? List.of() : value);
    }
    public List<ReportCollectionContext> convertToEntityAttribute(String value) {
        return value == null || value.isBlank() ? List.of()
                : List.copyOf(Arrays.asList(MAPPER.readValue(value, ReportCollectionContext[].class)));
    }
}
