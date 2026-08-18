package com.example.be.domain.articles.service;

import com.example.be.domain.articles.dto.res.ArticleResDTO;
import com.example.be.global.apiPayload.PageResponse;

import java.time.OffsetDateTime;

public interface ArticleQueryService {

    PageResponse<ArticleResDTO.Summary> getArticles(
            Long runId,
            Long topicId,
            Long sourceId,
            String changeType,
            String relevance,
            String riskLevel,
            String category,
            String language,
            OffsetDateTime from,
            OffsetDateTime to,
            String sort,
            int page,
            int size);

    ArticleResDTO.Detail getArticle(Long articleId, Long runId);
}
