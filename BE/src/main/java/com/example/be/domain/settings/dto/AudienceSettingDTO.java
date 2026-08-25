package com.example.be.domain.settings.dto;

import com.example.be.domain.analysis.entity.Audience;
import io.swagger.v3.oas.annotations.media.Schema;

public class AudienceSettingDTO {

    private AudienceSettingDTO() {
    }

    @Schema(name = "AudienceSettingUpdateRequest")
    public record UpdateRequest(String audience) {
    }

    @Schema(name = "AudienceSettingResponse")
    public record Response(Audience audience) {
    }
}
