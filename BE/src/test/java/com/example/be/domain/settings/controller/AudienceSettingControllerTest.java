package com.example.be.domain.settings.controller;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.settings.dto.AudienceSettingDTO;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.settings.service.AudienceSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AudienceSettingController.class)
class AudienceSettingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AudienceSettingService service;

    @Test
    void getsAndUpdatesAudienceWithCommonEnvelope() throws Exception {
        when(service.get()).thenReturn(new AudienceSettingDTO.Response(Audience.CHIP_MAKER));
        when(service.update(any())).thenReturn(new AudienceSettingDTO.Response(Audience.IT_INFRA));

        mockMvc.perform(get("/api/settings/audience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.audience").value("CHIP_MAKER"));

        mockMvc.perform(put("/api/settings/audience")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"IT_INFRA"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.audience").value("IT_INFRA"));
    }

    @Test
    void returnsAudience400ForUnknownAudience() throws Exception {
        when(service.update(any())).thenThrow(new AudienceException());

        mockMvc.perform(put("/api/settings/audience")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"audience":"OPERATOR"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUDIENCE400"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 관점입니다."));
    }
}
