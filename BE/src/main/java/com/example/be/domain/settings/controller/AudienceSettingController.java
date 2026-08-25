package com.example.be.domain.settings.controller;

import com.example.be.domain.settings.dto.AudienceSettingDTO;
import com.example.be.domain.settings.service.AudienceSettingService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settings/audience")
@Tag(name = "기본 관점 설정")
public class AudienceSettingController {

    private final AudienceSettingService service;

    @GetMapping
    @Operation(summary = "기본 관점 조회")
    public ApiResponse<AudienceSettingDTO.Response> get() {
        return ApiResponse.of(GeneralSuccessCode.OK, service.get());
    }

    @PutMapping
    @Operation(summary = "기본 관점 변경")
    public ApiResponse<AudienceSettingDTO.Response> update(
            @RequestBody AudienceSettingDTO.UpdateRequest request) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, service.update(request));
    }
}
