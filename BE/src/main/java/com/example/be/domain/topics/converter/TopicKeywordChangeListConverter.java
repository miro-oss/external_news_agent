package com.example.be.domain.topics.converter;

import com.example.be.domain.analysis.converter.JsonListConverter;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

@Converter
public class TopicKeywordChangeListConverter extends JsonListConverter<TopicKeywordChange> {

    public TopicKeywordChangeListConverter() {
        super(new TypeReference<>() {
        });
    }
}
