package com.example.be.domain.insights.converter;

import com.example.be.domain.analysis.converter.JsonListConverter;
import com.example.be.domain.insights.entity.InsightImplication;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

@Converter
public class InsightImplicationListConverter extends JsonListConverter<InsightImplication> {

    public InsightImplicationListConverter() {
        super(new TypeReference<>() {
        });
    }
}
