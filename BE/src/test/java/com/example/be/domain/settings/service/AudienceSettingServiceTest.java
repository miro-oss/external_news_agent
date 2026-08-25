package com.example.be.domain.settings.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.settings.dto.AudienceSettingDTO;
import com.example.be.domain.settings.entity.AppSetting;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.settings.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudienceSettingServiceTest {

    private final AppSettingRepository repository = mock(AppSettingRepository.class);
    private final AppSetting setting = mock(AppSetting.class);
    private final AudienceSettingService service = new AudienceSettingService(repository);

    @BeforeEach
    void setUp() {
        when(repository.findById(AppSetting.SINGLETON_ID)).thenReturn(Optional.of(setting));
        when(repository.findByIdForUpdate(AppSetting.SINGLETON_ID)).thenReturn(Optional.of(setting));
        when(setting.getDefaultAudience()).thenReturn(Audience.CHIP_MAKER);
    }

    @Test
    void getsAndUpdatesDefaultAudience() {
        doAnswer(invocation -> {
            when(setting.getDefaultAudience()).thenReturn(invocation.getArgument(0, Audience.class));
            return null;
        }).when(setting).updateAudience(any(), any());

        assertEquals(Audience.CHIP_MAKER, service.get().audience());
        AudienceSettingDTO.Response response = service.update(
                new AudienceSettingDTO.UpdateRequest(" it_infra "));

        verify(setting).updateAudience(eq(Audience.IT_INFRA), any());
        assertEquals(Audience.IT_INFRA, response.audience());
    }

    @Test
    void rejectsUnknownAudienceWithAudience400() {
        AudienceException exception = assertThrows(
                AudienceException.class,
                () -> service.update(new AudienceSettingDTO.UpdateRequest("operator")));

        assertEquals("AUDIENCE400", exception.getCode().getCode());
    }
}
