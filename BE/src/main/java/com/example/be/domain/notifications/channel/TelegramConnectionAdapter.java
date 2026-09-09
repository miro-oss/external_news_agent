package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Receives /start only. It never sends a message or takes over an existing webhook. */
@Component
@Slf4j
public class TelegramConnectionAdapter {
    private final RestClient client;
    private final NotificationProperties properties;
    private final AtomicBoolean receptionRequestLogged = new AtomicBoolean();
    public TelegramConnectionAdapter(@Qualifier("telegramRestClient") RestClient client, NotificationProperties properties) {
        this.client=client; this.properties=properties;
    }
    public boolean configured() { return StringUtils.hasText(properties.getTelegram().getBotToken()); }
    public String botUsername() {
        if (!configured()) {
            log.warn("텔레그램 연결 확인 실패. reason=BOT_TOKEN_MISSING");
            throw new NotificationTransportException("텔레그램 연결 설정이 준비되지 않았습니다.");
        }
        String operation = "getWebhookInfo";
        try {
            // Preserve ':' as shown in Telegram's documented URL instead of template-encoding it.
            WebhookResponse webhook=client.get().uri(uri -> uri.pathSegment("bot" + token(), "getWebhookInfo").build())
                    .retrieve().body(WebhookResponse.class);
            if (webhook == null || !webhook.ok() || webhook.result() == null) {
                log.warn("텔레그램 연결 확인 실패. operation={} reason=INVALID_RESPONSE", operation);
                throw unavailable();
            }
            boolean hasWebhook = StringUtils.hasText(webhook.result().url());
            List<String> allowedUpdates = webhook.result().allowedUpdates();
            boolean acceptsMessage = allowedUpdates == null || allowedUpdates.isEmpty() || allowedUpdates.contains("message");
            log.info("텔레그램 연결 수신 설정. hasWebhook={} pendingUpdateCount={} acceptsMessage={}",
                    hasWebhook, webhook.result().pendingUpdateCount() == null ? "UNKNOWN" : webhook.result().pendingUpdateCount(), acceptsMessage);
            if (hasWebhook) throw new NotificationTransportException("이 봇은 다른 연결 서비스를 사용 중입니다. 관리자에게 연결 설정 확인을 요청해 주세요.");
            operation = "getMe";
            MeResponse response=client.get().uri(uri -> uri.pathSegment("bot" + token(), "getMe").build())
                    .retrieve().body(MeResponse.class);
            if (response == null || !response.ok() || response.result() == null
                    || response.result().username() == null || !response.result().username().matches("[A-Za-z0-9_]{5,32}")) {
                log.warn("텔레그램 연결 확인 실패. operation={} reason=INVALID_RESPONSE", operation);
                throw unavailable();
            }
            return response.result().username();
        } catch (RestClientResponseException error) {
            log.warn("텔레그램 연결 확인 실패. operation={} httpStatus={}", operation, error.getStatusCode().value());
            throw unavailable();
        } catch (RestClientException error) {
            log.warn("텔레그램 연결 확인 실패. operation={} type={}", operation, error.getClass().getSimpleName());
            throw unavailable();
        }
    }
    public List<Update> updates(long offset) {
        try {
            UpdatesResponse response=client.post().uri(uri -> uri.pathSegment("bot" + token(), "getUpdates").build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("offset",offset,"timeout",0,"limit",100,"allowed_updates",List.of("message")))
                    .httpRequest(this::logReceptionRequestOnce)
                    .retrieve().body(UpdatesResponse.class);
            if (response==null || !response.ok() || response.result()==null) {
                log.warn("텔레그램 연결 업데이트 조회 실패. reason=INVALID_RESPONSE");
                throw unavailable();
            }
            return response.result();
        } catch (RestClientResponseException error) {
            log.warn("텔레그램 연결 업데이트 조회 실패. httpStatus={}", error.getStatusCode().value());
            throw unavailable();
        } catch (RestClientException error) {
            log.warn("텔레그램 연결 업데이트 조회 실패. type={}", error.getClass().getSimpleName());
            throw unavailable();
        }
    }
    private void logReceptionRequestOnce(ClientHttpRequest request) {
        if (!receptionRequestLogged.compareAndSet(false, true)) return;
        log.info("텔레그램 연결 수신 요청 형식. expectedPath={} hasQuery={} isPost={} isJson={}",
                ("/bot" + token() + "/getUpdates").equals(request.getURI().getRawPath()),
                request.getURI().getRawQuery() != null,
                HttpMethod.POST.equals(request.getMethod()),
                MediaType.APPLICATION_JSON.isCompatibleWith(request.getHeaders().getContentType()));
    }
    private String token() { return properties.getTelegram().getBotToken(); }
    private NotificationTransportException unavailable() { return new NotificationTransportException("텔레그램 연결 서버에 접속하지 못했습니다. 잠시 후 다시 시도해 주세요."); }
    @JsonIgnoreProperties(ignoreUnknown=true) private record WebhookResponse(boolean ok, Webhook result) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record Webhook(
            String url,
            @JsonProperty("pending_update_count") Integer pendingUpdateCount,
            @JsonProperty("allowed_updates") List<String> allowedUpdates) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record MeResponse(boolean ok, Bot result) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record Bot(String username) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record UpdatesResponse(boolean ok,List<Update> result) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record Update(@JsonProperty("update_id") long updateId, Message message) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record Message(String text,Chat chat, User from,long date) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record Chat(long id,String type) { }
    @JsonIgnoreProperties(ignoreUnknown=true) public record User(long id,@JsonProperty("is_bot") boolean bot) { }
}
