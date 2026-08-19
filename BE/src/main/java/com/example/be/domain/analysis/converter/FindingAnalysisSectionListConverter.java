package com.example.be.domain.analysis.converter;

import com.example.be.domain.analysis.entity.FindingAnalysisSection;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;

@Converter
public class FindingAnalysisSectionListConverter extends JsonListConverter<FindingAnalysisSection> {

    public FindingAnalysisSectionListConverter() {
        super(new TypeReference<>() {
        });
    }
}
