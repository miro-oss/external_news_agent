package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

@ExtendWith(OutputCaptureExtension.class)
class TelegramNotificationSenderTest {
    @Test void explicitHttpRejectionsCanRetryWithoutLeakingBotUrl(CapturedOutput output) {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.BAD_REQUEST,HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.TOO_MANY_REQUESTS}) {
            var failure=sendFailure(status);
            assertTrue(failure.isDefinitelyRejected());
            assertFalse(failure.getMessage().contains("synthetic-token"));
            assertNull(failure.getCause());
            assertTrue(output.getOut().contains("httpStatus=" + status.value() + " definitelyRejected=true"));
        }
        assertFalse(output.getAll().contains("synthetic"));
        assertFalse(output.getAll().contains("telegram.invalid"));
        assertFalse(output.getAll().contains("private-address"));
    }
    @Test void timeoutAndServerErrorsRemainUncertain() {
        assertFalse(sendFailure(HttpStatus.REQUEST_TIMEOUT).isDefinitelyRejected());
        assertFalse(sendFailure(HttpStatus.INTERNAL_SERVER_ERROR).isDefinitelyRejected());
    }
    @Test void onboardingFailureLogsStatusWithoutAddressOrToken(CapturedOutput output) {
        var builder=RestClient.builder().baseUrl("https://telegram.invalid");
        var server=MockRestServiceServer.bindTo(builder).build();
        var properties=new NotificationProperties();properties.getTelegram().setBotToken("123456:synthetic-token");
        var sender=new TelegramNotificationSender(properties,builder.build());
        server.expect(requestTo("https://telegram.invalid/bot123456:synthetic-token/getChat?chat_id=123"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"description\":\"synthetic-private-detail\"}"));

        assertFalse(sender.isOnboarded(NotificationChannel.builder().config(Map.of()).build(), "123"));

        assertTrue(output.getOut().contains("텔레그램 수신 주소 확인 실패. httpStatus=400"));
        assertFalse(output.getAll().contains("synthetic"));
        assertFalse(output.getAll().contains("telegram.invalid"));
        server.verify();
    }
    @Test void sendTransportFailureLogsOnlyExceptionType(CapturedOutput output) {
        var builder=RestClient.builder().baseUrl("https://telegram.invalid");
        var server=MockRestServiceServer.bindTo(builder).build();
        var properties=new NotificationProperties();properties.getTelegram().setBotToken("123456:synthetic-token");
        var sender=new TelegramNotificationSender(properties,builder.build());
        server.expect(requestTo("https://telegram.invalid/bot123456:synthetic-token/sendMessage"))
                .andRespond(withException(new IOException("synthetic-private-detail")));

        var error=assertThrows(NotificationTransportException.class,()->sender.send(
                NotificationChannel.builder().config(Map.of()).build(),"private-address",null,"synthetic-report-body"));

        assertFalse(error.isDefinitelyRejected());
        assertNull(error.getCause());
        assertTrue(output.getOut().contains("텔레그램 전송 실패. type=ResourceAccessException"));
        assertFalse(output.getAll().contains("synthetic"));
        assertFalse(output.getAll().contains("telegram.invalid"));
        assertFalse(output.getAll().contains("private-address"));
        server.verify();
    }
    private NotificationTransportException sendFailure(HttpStatus status) {
        var builder=RestClient.builder().baseUrl("https://telegram.invalid");
        var server=MockRestServiceServer.bindTo(builder).build();
        var properties=new NotificationProperties();properties.getTelegram().setBotToken("123456:synthetic-token");
        var sender=new TelegramNotificationSender(properties,builder.build());
        server.expect(requestTo("https://telegram.invalid/bot123456:synthetic-token/sendMessage"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"description\":\"synthetic-private-detail\"}"));
        var error=assertThrows(NotificationTransportException.class,()->sender.send(
                NotificationChannel.builder().config(Map.of()).build(),"private-address",null,"synthetic-report-body"));
        server.verify();return error;
    }
}
