package com.example.be.domain.notifications.controller;

import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.exception.code.NotificationErrorCode;
import com.example.be.domain.notifications.service.RecipientReportSubscriptionService;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecipientReportSubscriptionController.class)
class RecipientReportSubscriptionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RecipientReportSubscriptionService service;

    @Test
    void exposesCurrentScopesAndSavesOnlyThisRecipientsExclusions() throws Exception {
        var row = new RecipientReportSubscriptionService.TopicSubscription(1L, "HBM 시장", true,
                List.of("RUN", "DAILY"), List.of("DAILY"), List.of("WEEKLY"), List.of("EMAIL"), false, List.of("기술"));
        when(service.get(2L)).thenReturn(new RecipientReportSubscriptionService.Subscriptions(2L, List.of(row)));
        mvc.perform(get("/api/notifications/recipients/2/report-subscriptions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.recipientId").value(2))
                .andExpect(jsonPath("$.result.topics[0].includedScopes[0]").value("WEEKLY"))
                .andExpect(jsonPath("$.result.topics[0].excludedScopes[0]").value("DAILY"))
                .andExpect(jsonPath("$.result.topics[0].channelTypes[0]").value("EMAIL"));
        when(service.save(eq(2L), eq(1L), any())).thenReturn(row);
        mvc.perform(put("/api/notifications/recipients/2/report-subscriptions/1").contentType("application/json")
                        .content("{\"excludedScopes\":[\"DAILY\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.topicId").value(1));
        verify(service).save(2L, 1L, new RecipientReportSubscriptionService.Exclusions(List.of("DAILY")));
        mvc.perform(put("/api/notifications/recipients/2/report-subscriptions/1").contentType("application/json")
                        .content("{\"excludedScopes\":[],\"includedScopes\":[\"WEEKLY\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.includedScopes[0]").value("WEEKLY"));
        verify(service).save(2L, 1L, new RecipientReportSubscriptionService.Exclusions(List.of(), List.of("WEEKLY")));
    }

    @Test
    void patchesGlobalAggregateAndTopicRunChoicesTogether() throws Exception {
        when(service.update(eq(2L), any())).thenReturn(new RecipientReportSubscriptionService.Subscriptions(2L, List.of(),
                new RecipientReportSubscriptionService.Aggregates(true, false, List.of("EMAIL"))));
        mvc.perform(patch("/api/notifications/recipients/2/report-subscriptions").contentType("application/json")
                        .content("{\"daily\":true,\"topics\":[{\"topicId\":1,\"subscribed\":false}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.aggregates.daily").value(true))
                .andExpect(jsonPath("$.result.aggregates.weekly").value(false));
        verify(service).update(2L, new RecipientReportSubscriptionService.SettingsUpdate(true, null,
                List.of(new RecipientReportSubscriptionService.TopicChoice(1L, false))));
    }

    @Test
    void preservesSpecifiedValidationAndMissingRecipientErrors() throws Exception {
        when(service.save(eq(2L), eq(1L), any())).thenThrow(new GeneralException(GeneralErrorCode.BAD_REQUEST,
                "제외할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요."));
        mvc.perform(put("/api/notifications/recipients/2/report-subscriptions/1").contentType("application/json")
                        .content("{\"excludedScopes\":[\"MONTHLY\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.message").value("제외할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요."));
        when(service.get(999L)).thenThrow(new NotificationException(NotificationErrorCode.RECIPIENT_NOT_FOUND));
        mvc.perform(get("/api/notifications/recipients/999/report-subscriptions"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECIPIENT404"));
    }
}
