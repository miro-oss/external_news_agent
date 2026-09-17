package com.example.be.domain.notifications.controller;

import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.notifications.service.RunDeliverySettingsService;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RunDeliverySettingsController.class)
class RunDeliverySettingsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RunDeliverySettingsService service;

    @Test
    void returnsSpecifiedSettingsAndPendingReportState() throws Exception {
        when(service.get(42L)).thenReturn(new RunDeliverySettingsService.Settings(42L, true, 17L, false, "RUN",
                true, false, true, List.of(1L), List.of(), List.of(2L), List.of()));
        mvc.perform(get("/api/notifications/runs/42/delivery-settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.editable").value(true)).andExpect(jsonPath("$.result.reportId").value(17))
                .andExpect(jsonPath("$.result.reportReady").value(false)).andExpect(jsonPath("$.result.daily").value(true))
                .andExpect(jsonPath("$.result.topicPolicies").isEmpty());
    }

    @Test
    void returnsClosedConflictAndRunNotFoundContracts() throws Exception {
        when(service.save(eq(42L), any())).thenThrow(new GeneralException(GeneralErrorCode.CONFLICT, RunDeliverySettingsService.CLOSED_MESSAGE));
        mvc.perform(put("/api/notifications/runs/42/delivery-settings").contentType("application/json")
                        .content("{\"enabled\":false,\"run\":true,\"daily\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMON409"))
                .andExpect(jsonPath("$.message").value(RunDeliverySettingsService.CLOSED_MESSAGE));
        when(service.get(999L)).thenThrow(new RunException(RunErrorCode.RUN_NOT_FOUND));
        mvc.perform(get("/api/notifications/runs/999/delivery-settings"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RUN404"));
    }
}
