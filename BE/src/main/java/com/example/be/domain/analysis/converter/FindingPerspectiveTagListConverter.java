package com.example.be.domain.analysis.converter;

import com.example.be.domain.analysis.entity.FindingPerspectiveTag;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

@Converter
public class FindingPerspectiveTagListConverter extends JsonListConverter<FindingPerspectiveTag> {

    public FindingPerspectiveTagListConverter() {
        super(new TypeReference<>() {
        });
    }
}
