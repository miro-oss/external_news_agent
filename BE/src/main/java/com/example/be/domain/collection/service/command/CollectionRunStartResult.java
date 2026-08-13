package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.global.apiPayload.code.BaseSuccessCode;

public record CollectionRunStartResult(BaseSuccessCode successCode, CollectionRunResDTO.Created response) {
}
