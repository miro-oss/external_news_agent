package com.example.be.domain.issues.converter;

import com.example.be.domain.issues.entity.IssueCrossSource;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Converter
public class IssueCrossSourceConverter implements AttributeConverter<IssueCrossSource, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(IssueCrossSource attribute) {
        return OBJECT_MAPPER.writeValueAsString(attribute == null ? IssueCrossSource.empty() : attribute);
    }

    @Override
    public IssueCrossSource convertToEntityAttribute(String dbData) {
        return StringUtils.hasText(dbData)
                ? OBJECT_MAPPER.readValue(dbData, IssueCrossSource.class)
                : IssueCrossSource.empty();
    }
}
