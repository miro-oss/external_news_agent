package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "news.integration.mailpit", matches = "true")
class EmailNotificationSenderMailpitIntegrationTest {

    @Test
    void sendsHtmlReportAndExposesMessageThroughMailpit() throws Exception {
        NotificationProperties properties = new NotificationProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
        EmailNotificationSender sender = new EmailNotificationSender(properties);
        NotificationChannel channel = NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일 보고서")
                .config(Map.of("host", "localhost", "port", "1025", "from", "news-agent@local.test",
                        "ssl", "false", "startTls", "false"))
                .maxLength(Integer.MAX_VALUE).active(true).build();

        String messageId = sender.send(channel, "m6-recipient@local.test",
                "[M6] Mailpit 확인", "<h2>알림 채널 통합 테스트</h2><p>요약과 링크만 전송합니다.</p>");

        assertFalse(messageId.isBlank());
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:8025/api/v1/messages")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300);
        assertTrue(response.body().contains("m6-recipient@local.test"));
    }
}
