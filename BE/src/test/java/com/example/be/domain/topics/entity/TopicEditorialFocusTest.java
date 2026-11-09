package com.example.be.domain.topics.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicEditorialFocusTest {
    @Test
    void recognizesExplicitTechnologyTopicsInEitherLanguage() {
        for (String query : List.of("AI 기술", "인공지능 기술", "AI technology", "AI technical updates",
                "AI 모델", "AI Agent", "LLM 검색 추론", "LLM", "AI 알고리즘",
                "인공지능 에이전트", "GraphRAG", "온톨로지 지식 그래프", "AI model release")) {
            assertTrue(TopicEditorialFocus.isAiTechnology(topic("기술 소식", query)), query);
        }
    }

    @Test
    void doesNotNarrowBroadOrExplicitIndustryQueriesOrOtherDomains() {
        for (String query : List.of("인공지능", "AI", "AI 산업", "AI 모델 투자", "AI 인프라",
                "AI 기술 산업 동향", "AI technology investment", "LLM market", "AI 데이터센터", "AI model regulation", "HBM 반도체", "Taiwan models",
                "insurance agent", "그래프 알고리즘")) {
            assertFalse(TopicEditorialFocus.isAiTechnology(topic("AI 모델 기술", query)), query);
        }
    }

    @Test
    void usesNameOnlyWhenSearchQueryIsAbsent() {
        assertTrue(TopicEditorialFocus.isAiTechnology(topic("AI 모델 업데이트", null)));
        assertFalse(TopicEditorialFocus.isAiTechnology(topic("AI 모델 업데이트", "인공지능")));
        assertFalse(TopicEditorialFocus.isAiTechnology(null));
        assertFalse(TopicEditorialFocus.isAiTechnology(topic(null, null)));
    }

    private Topic topic(String name, String query) {
        return Topic.builder().name(name).queryText(query).build();
    }
}
