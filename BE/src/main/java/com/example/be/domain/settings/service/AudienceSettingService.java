package com.example.be.domain.settings.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.settings.dto.AudienceSettingDTO;
import com.example.be.domain.settings.entity.AppSetting;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.settings.repository.AppSettingRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AudienceSettingService {

    private final AppSettingRepository repository;

    @Transactional(readOnly = true)
    public AudienceSettingDTO.Response get() {
        return response(repository.findById(AppSetting.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("app_settings 단일 행이 없습니다.")));
    }

    @Transactional
    public AudienceSettingDTO.Response update(AudienceSettingDTO.UpdateRequest request) {
        Audience audience = parse(request == null ? null : request.audience());
        AppSetting setting = repository.findByIdForUpdate(AppSetting.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("app_settings 단일 행이 없습니다."));
        setting.updateAudience(audience, LocalDateTime.now(ApiTimeZone.ZONE));
        return response(setting);
    }

    private Audience parse(String value) {
        try {
            return Audience.fromApiValue(value);
        } catch (IllegalArgumentException exception) {
            throw new AudienceException();
        }
    }

    private AudienceSettingDTO.Response response(AppSetting setting) {
        return new AudienceSettingDTO.Response(setting.getDefaultAudience());
    }
}
