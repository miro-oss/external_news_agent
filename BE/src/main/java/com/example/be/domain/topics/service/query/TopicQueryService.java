package com.example.be.domain.topics.service.query;

import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.global.apiPayload.PageResponse;

public interface TopicQueryService {

    PageResponse<TopicResDTO.Summary> getTopics(Boolean active, String keyword, int page, int size);

    TopicResDTO.Detail getTopic(Long topicId);
}
