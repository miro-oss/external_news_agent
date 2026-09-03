package com.example.be.domain.topics.service.query;

import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;
import com.example.be.global.apiPayload.PageResponse;

public interface TopicKeywordProposalQueryService {

    PageResponse<TopicKeywordProposalResDTO.Item> getKeywordProposals(String status, int page, int size);
}
