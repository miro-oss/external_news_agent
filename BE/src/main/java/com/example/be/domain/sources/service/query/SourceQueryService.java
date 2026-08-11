package com.example.be.domain.sources.service.query;

import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.global.apiPayload.PageResponse;

public interface SourceQueryService {

    PageResponse<SourceResDTO.Summary> getSources(String sourceKind, Boolean active, String keyword, int page, int size);

    SourceResDTO.Detail getSource(Long sourceId);
}
