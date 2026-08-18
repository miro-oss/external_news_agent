package com.example.be.domain.analysis.converter;

import com.example.be.domain.analysis.entity.FindingKeyPoint;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Converter
public class FindingKeyPointListConverter implements AttributeConverter<List<FindingKeyPoint>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<FindingKeyPoint>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<FindingKeyPoint> attribute) {
        return OBJECT_MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<FindingKeyPoint> convertToEntityAttribute(String dbData) {
        return StringUtils.hasText(dbData) ? OBJECT_MAPPER.readValue(dbData, TYPE) : List.of();
    }
}
