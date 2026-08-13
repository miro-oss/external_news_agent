package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;

public interface CollectionRunCommandService {

    CollectionRunStartResult startManualRun(CollectionRunReqDTO.Create request);
}
