package com.example.be.domain.topics.converter;

import com.example.be.domain.analysis.converter.JsonListConverter;
import com.example.be.domain.topics.entity.TopicKeywordAppliedChange;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

import java.util.List;

@Converter
public class TopicKeywordAppliedChangeListConverter extends JsonListConverter<TopicKeywordAppliedChange> {

    public TopicKeywordAppliedChangeListConverter() {
        super(new TypeReference<>() {});
    }

    @Override
    public String convertToDatabaseColumn(List<TopicKeywordAppliedChange> value) {
        return value == null ? null : super.convertToDatabaseColumn(value);
    }

    @Override
    public List<TopicKeywordAppliedChange> convertToEntityAttribute(String value) {
        // NULL is an older approval without mutation history; [] is a tracked no-op approval.
        return value == null ? null : super.convertToEntityAttribute(value);
    }
}
