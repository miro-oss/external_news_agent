package com.example.be.domain.topics.converter;

import com.example.be.domain.analysis.converter.JsonListConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.List;

@Converter
public class TopicKeywordSelectionListConverter extends JsonListConverter<Integer> {

    public TopicKeywordSelectionListConverter() {
        super(new TypeReference<>() {});
    }

    @Override
    public String convertToDatabaseColumn(List<Integer> value) {
        return value == null ? null : super.convertToDatabaseColumn(value);
    }

    @Override
    public List<Integer> convertToEntityAttribute(String value) {
        // NULL distinguishes unreviewed proposals and legacy full approvals from an explicit selection.
        return value == null ? null : super.convertToEntityAttribute(value);
    }
}
