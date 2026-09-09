package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
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
                    .uri(uri -> uri.pathSegment("bot" + properties.getTelegram().getBotToken(), "getChat")
                            .queryParam("chat_id", address)
                            .build())
                    .retrieve()
                    .body(TelegramResponse.class);
            boolean onboarded = response != null && response.ok();
            if (!onboarded) log.warn("텔레그램 수신 주소 확인 실패. reason=INVALID_RESPONSE");
            return onboarded;
        } catch (RestClientResponseException exception) {
            log.warn("텔레그램 수신 주소 확인 실패. httpStatus={}", exception.getStatusCode().value());
            return false;
        } catch (RestClientException exception) {
            log.warn("텔레그램 수신 주소 확인 실패. type={}", exception.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public String send(NotificationChannel channel, String address, String subject, String body) {
        if (!isConfigured(channel)) {
            log.warn("텔레그램 전송 실패. reason=BOT_TOKEN_MISSING");
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
                    // Keep Telegram's ':' token separator while encoding the path segment safely.
                    .uri(uri -> uri.pathSegment("bot" + properties.getTelegram().getBotToken(), "sendMessage").build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TelegramResponse.class);
            if (response != null && !response.ok()) {
                log.warn("텔레그램 전송 실패. reason=PROVIDER_REJECTED");
                throw new NotificationTransportException("텔레그램 전송이 거절되었습니다.", true);
            }
            if (response == null || response.result() == null || response.result().messageId() == null) {
                log.warn("텔레그램 전송 실패. reason=INVALID_RESPONSE");
                throw new NotificationTransportException("텔레그램 전송 결과를 확인하지 못했습니다.");
            }
            return String.valueOf(response.result().messageId());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            // Explicit client rejection (including rate limiting) did not accept a message.
            // A request timeout is ambiguous; never include the token-bearing request URI or body.
            boolean rejected = status >= 400 && status < 500 && status != 408;
            log.warn("텔레그램 전송 실패. httpStatus={} definitelyRejected={}", status, rejected);
            throw new NotificationTransportException(status == 429
                    ? "텔레그램 전달이 잠시 제한되었습니다. 잠시 후 다시 시도합니다."
                    : rejected ? "텔레그램 전송이 거절되었습니다. 연결 상태를 확인해 주세요."
                    : "텔레그램 전송 결과를 확인하지 못했습니다.", rejected);
        } catch (RestClientException exception) {
            log.warn("텔레그램 전송 실패. type={}", exception.getClass().getSimpleName());
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
