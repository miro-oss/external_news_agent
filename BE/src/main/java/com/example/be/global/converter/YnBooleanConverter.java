package com.example.be.global.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Oracle 컬럼의 'Y' / 'N' 값을 boolean으로 매핑한다.
 */
@Converter
public class YnBooleanConverter implements AttributeConverter<Boolean, String> {

    private static final String YES = "Y";
    private static final String NO = "N";

    @Override
    public String convertToDatabaseColumn(Boolean attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute ? YES : NO;
    }

    @Override
    public Boolean convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return YES.equalsIgnoreCase(dbData.trim());
    }
}
