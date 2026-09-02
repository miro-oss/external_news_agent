package com.example.be.domain.insights.converter;

import com.example.be.domain.analysis.converter.JsonListConverter;
import com.example.be.domain.insights.entity.InsightFact;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

@Converter
public class InsightFactListConverter extends JsonListConverter<InsightFact> {

    public InsightFactListConverter() {
        super(new TypeReference<>() {
        });
    }
}
