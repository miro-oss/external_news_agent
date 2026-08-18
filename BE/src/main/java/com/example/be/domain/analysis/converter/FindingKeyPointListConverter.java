package com.example.be.domain.analysis.converter;

import com.example.be.domain.analysis.entity.FindingKeyPoint;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

@Converter
public class FindingKeyPointListConverter extends JsonListConverter<FindingKeyPoint> {

    public FindingKeyPointListConverter() {
        super(new TypeReference<>() {
        });
    }
}
