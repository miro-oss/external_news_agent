package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TelegramNotificationSenderTest {
    @Test void explicitHttpRejectionsCanRetryWithoutLeakingBotUrl() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.BAD_REQUEST,HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.TOO_MANY_REQUESTS}) {
            var failure=sendFailure(status);
            assertTrue(failure.isDefinitelyRejected());
            assertFalse(failure.getMessage().contains("synthetic-token"));
            assertNull(failure.getCause());
        }
    }
    @Test void timeoutAndServerErrorsRemainUncertain() {
        assertFalse(sendFailure(HttpStatus.REQUEST_TIMEOUT).isDefinitelyRejected());
        assertFalse(sendFailure(HttpStatus.INTERNAL_SERVER_ERROR).isDefinitelyRejected());
    }
    private NotificationTransportException sendFailure(HttpStatus status) {
        var builder=RestClient.builder().baseUrl("https://telegram.invalid");
        var server=MockRestServiceServer.bindTo(builder).build();
        var properties=new NotificationProperties();properties.getTelegram().setBotToken("synthetic-token");
        var sender=new TelegramNotificationSender(properties,builder.build());
        server.expect(requestTo("https://telegram.invalid/botsynthetic-token/sendMessage"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body("{\"ok\":false}"));
        var error=assertThrows(NotificationTransportException.class,()->sender.send(
                NotificationChannel.builder().config(Map.of()).build(),"123",null,"요약"));
        server.verify();return error;
    }
}
