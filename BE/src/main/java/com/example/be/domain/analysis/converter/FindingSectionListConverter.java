package com.example.be.domain.analysis.converter;

import com.example.be.domain.analysis.entity.FindingSection;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Converter
public class FindingSectionListConverter implements AttributeConverter<List<FindingSection>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<FindingSection>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<FindingSection> attribute) {
        return OBJECT_MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<FindingSection> convertToEntityAttribute(String dbData) {
        return StringUtils.hasText(dbData) ? OBJECT_MAPPER.readValue(dbData, TYPE) : List.of();
    }
}
