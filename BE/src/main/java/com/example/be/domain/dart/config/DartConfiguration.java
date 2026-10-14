package com.example.be.domain.dart.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DartProperties.class)
class DartConfiguration { }
