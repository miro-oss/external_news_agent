package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TelegramNotificationSender implements NotificationSender {

    private final NotificationProperties properties;
    private final RestClient restClient;

    public TelegramNotificationSender(NotificationProperties properties,
                                      @Qualifier("telegramRestClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public boolean isConfigured(NotificationChannel channel) {
        return StringUtils.hasText(properties.getTelegram().getBotToken());
    }

    @Override
    public boolean isOnboarded(NotificationChannel channel, String address) {
        if (!isConfigured(channel)) {
            return false;
        }
        try {
            TelegramResponse response = restClient.get()
                    .uri(uri -> uri.path("/bot{token}/getChat")
                            .queryParam("chat_id", address)
                            .build(properties.getTelegram().getBotToken()))
                    .retrieve()
                    .body(TelegramResponse.class);
            return response != null && response.ok();
        } catch (RestClientException exception) {
            return false;
        }
    }

    @Override
    public String send(NotificationChannel channel, String address, String subject, String body) {
        if (!isConfigured(channel)) {
            throw new NotificationTransportException("텔레그램 봇 토큰이 설정되지 않았습니다.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chat_id", address);
        request.put("text", body);
        request.put("parse_mode", "HTML");
        request.put("disable_web_page_preview",
                channel.getConfig().getOrDefault("disableWebPagePreview", Boolean.TRUE));
        try {
            TelegramResponse response = restClient.post()
                    .uri("/bot{token}/sendMessage", properties.getTelegram().getBotToken())
                    .body(request)
                    .retrieve()
                    .body(TelegramResponse.class);
            if (response != null && !response.ok()) {
                throw new NotificationTransportException("텔레그램 전송이 거절되었습니다.", true);
            }
            if (response == null || response.result() == null || response.result().messageId() == null) {
                throw new NotificationTransportException("텔레그램 전송 결과를 확인하지 못했습니다.");
            }
            return String.valueOf(response.result().messageId());
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            // Explicit client rejection (including rate limiting) did not accept a message.
            // A request timeout is ambiguous; never include the token-bearing request URI or body.
            boolean rejected = status >= 400 && status < 500 && status != 408;
            throw new NotificationTransportException(status == 429
                    ? "텔레그램 전달이 잠시 제한되었습니다. 잠시 후 다시 시도합니다."
                    : rejected ? "텔레그램 전송이 거절되었습니다. 연결 상태를 확인해 주세요."
                    : "텔레그램 전송 결과를 확인하지 못했습니다.", rejected);
        } catch (RestClientException exception) {
            // Spring transport 예외에는 bot token이 포함된 요청 URI가 들어갈 수 있어 cause를 노출하지 않는다.
            throw new NotificationTransportException("텔레그램 전송에 실패했습니다.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TelegramResponse(boolean ok, TelegramResult result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TelegramResult(@com.fasterxml.jackson.annotation.JsonProperty("message_id") Long messageId) {
    }
}
