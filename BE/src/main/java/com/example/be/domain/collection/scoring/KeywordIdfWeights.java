package com.example.be.domain.collection.scoring;

import java.util.Collection;
import java.util.Map;

/** 최근 기사 corpus에서 계산한 언어별 선택 키워드 가중치를 제공한다. */
@FunctionalInterface
public interface KeywordIdfWeights {

    Map<String, Double> weights(String language, Collection<String> keywords);
}
