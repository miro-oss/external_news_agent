package com.example.be.domain.collection.config;

import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CollectionPipelineProperties.class, AnalysisSelectionProperties.class})
public class CollectionPipelineConfiguration {
}
