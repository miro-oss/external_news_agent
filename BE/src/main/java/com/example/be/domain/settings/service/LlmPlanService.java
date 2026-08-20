package com.example.be.domain.settings.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.entity.AppSetting;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.exception.LlmException;
import com.example.be.domain.settings.exception.code.LlmErrorCode;
import com.example.be.domain.settings.repository.AppSettingRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class LlmPlanService {

    private final AppSettingRepository repository;
    private final AgentProperties properties;

    @Transactional(readOnly = true)
    public LlmSettingDTO.PlanResponse get() {
        AppSetting setting = setting();
        return response(setting);
    }

    @Transactional
    public LlmSettingDTO.PlanResponse update(LlmSettingDTO.UpdateRequest request) {
        if (request == null) {
            throw invalid("변경할 plan이 필요합니다.");
        }
        AppSetting setting = repository.findByIdForUpdate(AppSetting.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("app_settings 단일 행이 없습니다."));
        AgentPlan plan = parsePlan(request.plan(), "plan");
        PaidExhaustedAction action = StringUtils.hasText(request.paidExhaustedAction())
                ? parseAction(request.paidExhaustedAction())
                : setting.getPaidExhaustedAction();
        setting.update(plan, action, LocalDateTime.now(ApiTimeZone.ZONE));
        return response(setting);
    }

    @Transactional(readOnly = true)
    public AgentPlan resolveRunPlan(String override) {
        if (!StringUtils.hasText(override)) {
            return setting().getLlmPlan();
        }
        if (!properties.isAllowRunOverride()) {
            throw invalid("실행별 LLM 플랜 override가 비활성화되어 있습니다.");
        }
        return parsePlan(override, "plan");
    }

    @Transactional(readOnly = true)
    public PaidExhaustedAction paidExhaustedAction() {
        return setting().getPaidExhaustedAction();
    }

    private AppSetting setting() {
        return repository.findById(AppSetting.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("app_settings 단일 행이 없습니다."));
    }

    private LlmSettingDTO.PlanResponse response(AppSetting setting) {
        return new LlmSettingDTO.PlanResponse(
                setting.getLlmPlan(),
                properties.isAllowRunOverride(),
                setting.getPaidExhaustedAction());
    }

    private AgentPlan parsePlan(String value, String field) {
        try {
            return AgentPlan.valueOf(normalize(value));
        } catch (IllegalArgumentException exception) {
            throw invalid(field + "은 FREE 또는 PAID여야 합니다.");
        }
    }

    private PaidExhaustedAction parseAction(String value) {
        try {
            return PaidExhaustedAction.valueOf(normalize(value));
        } catch (IllegalArgumentException exception) {
            throw invalid("paidExhaustedAction은 STUB 또는 FALLBACK_FREE여야 합니다.");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private LlmException invalid(String message) {
        return new LlmException(LlmErrorCode.INVALID_PLAN, message);
    }
}
