package com.example.be.domain.topics.service.query;

import com.example.be.domain.topics.dto.res.TopicSourceResDTO;

public interface TopicSourceQueryService {

    TopicSourceResDTO.CombinationPage getCombinations(Long topicId,
                                                      Long sourceId,
                                                      Boolean active,
                                                      int page,
                                                      int size);
}
