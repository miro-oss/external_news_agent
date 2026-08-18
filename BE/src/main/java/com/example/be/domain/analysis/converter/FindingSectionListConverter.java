package com.example.be.domain.analysis.converter;

import com.example.be.domain.analysis.entity.FindingSection;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

@Converter
public class FindingSectionListConverter extends JsonListConverter<FindingSection> {

    public FindingSectionListConverter() {
        super(new TypeReference<>() {
        });
    }
}
