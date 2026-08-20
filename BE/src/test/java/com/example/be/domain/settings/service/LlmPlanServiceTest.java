package com.example.be.domain.settings.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.entity.AppSetting;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.exception.LlmException;
import com.example.be.domain.settings.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmPlanServiceTest {

    private final AppSettingRepository repository = mock(AppSettingRepository.class);
    private final AgentProperties properties = new AgentProperties();
    private final AppSetting setting = mock(AppSetting.class);
    private final LlmPlanService service = new LlmPlanService(repository, properties);

    @BeforeEach
    void setUp() {
        when(repository.findById(AppSetting.SINGLETON_ID)).thenReturn(Optional.of(setting));
        when(setting.getLlmPlan()).thenReturn(AgentPlan.FREE);
        when(setting.getPaidExhaustedAction()).thenReturn(PaidExhaustedAction.STUB);
    }

    @Test
    void resolvesOverrideBeforeSavedPlan() {
        assertEquals(AgentPlan.PAID, service.resolveRunPlan(" paid "));
        assertEquals(AgentPlan.FREE, service.resolveRunPlan(null));
    }

    @Test
    void rejectsOverrideWhenDisabled() {
        properties.setAllowRunOverride(false);

        LlmException exception = assertThrows(
                LlmException.class,
                () -> service.resolveRunPlan("PAID"));

        assertEquals("PLAN400", exception.getCode().getCode());
    }

    @Test
    void updatesPlanAndExhaustedActionTogether() {
        when(repository.findByIdForUpdate(AppSetting.SINGLETON_ID)).thenReturn(Optional.of(setting));

        LlmSettingDTO.PlanResponse response = service.update(
                new LlmSettingDTO.UpdateRequest("PAID", "FALLBACK_FREE"));

        verify(setting).update(eq(AgentPlan.PAID), eq(PaidExhaustedAction.FALLBACK_FREE), any());
        assertEquals(AgentPlan.FREE, response.plan());
    }

    @Test
    void rejectsUnknownPlan() {
        LlmException exception = assertThrows(
                LlmException.class,
                () -> service.resolveRunPlan("AUTO"));

        assertEquals("PLAN400", exception.getCode().getCode());
    }
}
