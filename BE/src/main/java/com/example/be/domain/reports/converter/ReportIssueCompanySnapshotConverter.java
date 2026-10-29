package com.example.be.domain.reports.converter;

import com.example.be.domain.reports.dart.ReportIssueCompanySnapshot;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Converter
public class ReportIssueCompanySnapshotConverter implements AttributeConverter<ReportIssueCompanySnapshot, String> {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build();

    public String convertToDatabaseColumn(ReportIssueCompanySnapshot value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public ReportIssueCompanySnapshot convertToEntityAttribute(String value) {
        return value == null || value.isBlank() ? null : MAPPER.readValue(value, ReportIssueCompanySnapshot.class);
    }
}
