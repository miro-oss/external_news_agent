package com.example.be.domain.collection.service.query;

import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.global.apiPayload.PageResponse;

import java.time.OffsetDateTime;

public interface CollectionRunQueryService {

    PageResponse<CollectionRunResDTO.Summary> getRuns(String status,
                                                       String triggerType,
                                                       Long topicId,
                                                       OffsetDateTime from,
                                                       OffsetDateTime to,
                                                       int page,
                                                       int size);

    CollectionRunResDTO.Detail getRun(Long runId);
}
