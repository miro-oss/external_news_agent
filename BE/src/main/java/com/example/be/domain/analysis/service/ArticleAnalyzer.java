package com.example.be.domain.analysis.service;

import com.example.be.domain.collection.entity.Article;

/** 실제 LLM 어댑터로 교체할 M4 분석 경계. */
public interface ArticleAnalyzer {

    AnalysisResult analyze(Article article);
}
