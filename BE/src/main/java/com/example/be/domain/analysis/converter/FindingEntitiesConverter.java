package com.example.be.domain.analysis.converter;

import com.example.be.domain.analysis.entity.FindingEntities;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Converter
public class FindingEntitiesConverter implements AttributeConverter<FindingEntities, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(FindingEntities attribute) {
        return OBJECT_MAPPER.writeValueAsString(
                attribute == null ? FindingEntities.empty() : attribute);
    }

    @Override
    public FindingEntities convertToEntityAttribute(String dbData) {
        return StringUtils.hasText(dbData)
                ? OBJECT_MAPPER.readValue(dbData, FindingEntities.class)
                : FindingEntities.empty();
    }
}
