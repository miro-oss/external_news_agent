package com.example.be.domain.notifications.controller;

import com.example.be.domain.notifications.dto.res.NotificationResDTO;
import com.example.be.domain.notifications.service.DeliveryLogQueryService;
import com.example.be.domain.notifications.service.NotificationDeliveryService;
import com.example.be.domain.notifications.service.NotificationManagementService;
import com.example.be.global.apiPayload.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private NotificationManagementService managementService;
    @MockitoBean private NotificationDeliveryService deliveryService;
    @MockitoBean private DeliveryLogQueryService logQueryService;

    @Test
    void channelListUsesNotificationPrefixAndHidesSecrets() throws Exception {
        when(managementService.getChannels(null)).thenReturn(List.of(NotificationResDTO.Channel.builder()
                .id(1L).channelType("TELEGRAM").name("텔레그램 속보")
                .config(Map.of("parseMode", "HTML")).maxLength(3500).active(true).tokenConfigured(true).build()));

        mockMvc.perform(get("/api/notifications/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result[0].channelType").value("TELEGRAM"))
                .andExpect(jsonPath("$.result[0].tokenConfigured").value(true))
                .andExpect(jsonPath("$.result[0].config.botToken").doesNotExist());
    }

    @Test
    void channelPatchReturnsSpecifiedUpdateMessage() throws Exception {
        when(managementService.updateChannel(any(), any())).thenReturn(NotificationResDTO.Channel.builder()
                .id(1L).channelType("TELEGRAM").name("텔레그램 속보")
                .config(Map.of("parseMode", "HTML")).maxLength(3500).active(false).build());

        mockMvc.perform(patch("/api/notifications/channels/1")
                        .contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.active").value(false));
    }

    @Test
    void recipientListUsesCommonPagingShape() throws Exception {
        when(managementService.getRecipients(null, null, null, null, 0, 20))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/notifications/recipients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.hasNext").value(false));
    }
}
