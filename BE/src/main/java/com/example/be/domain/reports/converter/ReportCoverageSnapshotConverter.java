package com.example.be.domain.reports.converter;

import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Converter
public class ReportCoverageSnapshotConverter implements AttributeConverter<ReportCoverageSnapshot, String> {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build();

    public String convertToDatabaseColumn(ReportCoverageSnapshot value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public ReportCoverageSnapshot convertToEntityAttribute(String value) {
        return value == null || value.isBlank() ? null : MAPPER.readValue(value, ReportCoverageSnapshot.class);
    }
}
