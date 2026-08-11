package com.example.be.domain.sources.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 정책을 JSON 객체 문자열로 저장한다. 대상 컬럼에는 IS JSON 제약이 걸려 있다.
 * 정책이 없으면 null을 저장한다 — Oracle의 IS JSON CHECK는 NULL을 통과시킨다.
 */
@Converter
public class CrawlPolicyConverter implements AttributeConverter<CrawlPolicy, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(CrawlPolicy attribute) {
        return attribute == null ? null : OBJECT_MAPPER.writeValueAsString(attribute);
    }

    @Override
    public CrawlPolicy convertToEntityAttribute(String dbData) {
        return StringUtils.hasText(dbData) ? OBJECT_MAPPER.readValue(dbData, CrawlPolicy.class) : null;
    }
}
