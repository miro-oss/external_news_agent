package com.example.be.domain.sources.service.command;

import com.example.be.domain.sources.dto.req.SourceReqDTO;
import com.example.be.domain.sources.dto.res.SourceResDTO;

public interface SourceCommandService {

    SourceResDTO.Created createSource(SourceReqDTO.Create request);

    SourceResDTO.Updated updateSource(Long sourceId, SourceReqDTO.Update request);

    SourceResDTO.Deleted deleteSource(Long sourceId);
}
