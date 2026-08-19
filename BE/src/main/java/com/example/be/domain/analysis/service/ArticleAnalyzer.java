package com.example.be.domain.analysis.service;

import com.example.be.domain.collection.entity.Article;

/** 수집 실행 문맥과 무관하게 기사 한 건을 분석하는 도메인 경계. */
public interface ArticleAnalyzer {

    AnalysisResult analyze(Article article);
}
