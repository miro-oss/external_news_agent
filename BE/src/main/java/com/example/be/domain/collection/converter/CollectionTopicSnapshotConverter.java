package com.example.be.domain.collection.converter;

import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;

@Converter
public class CollectionTopicSnapshotConverter implements AttributeConverter<CollectionTopicSnapshot, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public String convertToDatabaseColumn(CollectionTopicSnapshot value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }
    public CollectionTopicSnapshot convertToEntityAttribute(String value) {
        return value == null || value.isBlank() ? null : MAPPER.readValue(value, CollectionTopicSnapshot.class);
    }
}
