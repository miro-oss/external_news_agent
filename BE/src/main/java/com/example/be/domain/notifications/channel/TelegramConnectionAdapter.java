package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.List;

/** Receives /start only. It never sends a message or takes over an existing webhook. */
@Component
public class TelegramConnectionAdapter {
    private final RestClient client;
    private final NotificationProperties properties;
    public TelegramConnectionAdapter(@Qualifier("telegramRestClient") RestClient client, NotificationProperties properties) {
        this.client=client; this.properties=properties;
    }
    public boolean configured() { return StringUtils.hasText(properties.getTelegram().getBotToken()); }
    public String botUsername() {
        if (!configured()) throw new NotificationTransportException("텔레그램 연결 설정이 준비되지 않았습니다.");
        try {
            WebhookResponse webhook=client.get().uri("/bot{token}/getWebhookInfo",token()).retrieve().body(WebhookResponse.class);
            if (webhook == null || !webhook.ok() || webhook.result() == null) throw unavailable();
            if (StringUtils.hasText(webhook.result().url())) throw new NotificationTransportException("이 봇은 다른 연결 서비스를 사용 중입니다. 관리자에게 연결 설정 확인을 요청해 주세요.");
            MeResponse response=client.get().uri("/bot{token}/getMe",token()).retrieve().body(MeResponse.class);
            if (response == null || !response.ok() || response.result() == null
                    || response.result().username() == null || !response.result().username().matches("[A-Za-z0-9_]{5,32}")) throw unavailable();
            return response.result().username();
        } catch (RestClientException error) { throw unavailable(); }
    }
    public List<Update> updates(long offset) {
        try {
            UpdatesResponse response=client.get().uri(builder -> builder.path("/bot{token}/getUpdates")
                    .queryParam("offset",offset).queryParam("timeout",0).queryParam("limit",100)
                    .queryParam("allowed_updates","[\"message\"]").build(token())).retrieve().body(UpdatesResponse.class);
            if (response==null || !response.ok() || response.result()==null) throw unavailable();
            return response.result();
        } catch (RestClientException error) { throw unavailable(); }
    }
    private String token() { return properties.getTelegram().getBotToken(); }
    private NotificationTransportException unavailable() { return new NotificationTransportException("텔레그램 연결 서버에 접속하지 못했습니다. 잠시 후 다시 시도해 주세요."); }
    @JsonIgnoreProperties(ignoreUnknown=true) private record WebhookResponse(boolean ok, Webhook result) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record Webhook(String url) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record MeResponse(boolean ok, Bot result) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record Bot(String username) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record UpdatesResponse(boolean ok,List<Update> result) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record Update(@JsonProperty("update_id") long updateId, Message message) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record Message(String text,Chat chat, User from,long date) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record Chat(long id,String type) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record User(long id,@JsonProperty("is_bot") boolean bot) { }
}
