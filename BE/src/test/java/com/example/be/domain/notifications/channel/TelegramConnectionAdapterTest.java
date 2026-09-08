package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class TelegramConnectionAdapterTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://telegram.invalid");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void resolvesBotUsernameFromTelegramResponsesWithoutSendingMessages(CapturedOutput output) {
        server.expect(requestTo("https://telegram.invalid/botsynthetic-token/getWebhookInfo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"url":"","has_custom_certificate":false,"pending_update_count":0}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://telegram.invalid/botsynthetic-token/getMe"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"id":123,"is_bot":true,"first_name":"Example",
                        "username":"example_bot","can_join_groups":true,"can_read_all_group_messages":false,
                        "supports_inline_queries":false}}
                        """, MediaType.APPLICATION_JSON));

        assertEquals("example_bot", adapter().botUsername());
        assertTrue(output.getOut().contains("hasWebhook=false pendingUpdateCount=0 acceptsMessage=true"));
        assertFalse(output.getAll().contains("example_bot"));
        assertFalse(output.getAll().contains("synthetic-token"));
        server.verify();
    }

    @Test
    void preservesExistingWebhookWithoutTryingToCreateAConnection(CapturedOutput output) {
        server.expect(requestTo("https://telegram.invalid/botsynthetic-token/getWebhookInfo"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"url":"https://existing.invalid/private-webhook/synthetic-secret",
                        "pending_update_count":2,"allowed_updates":["callback_query"]}}
                        """, MediaType.APPLICATION_JSON));

        var error = assertThrows(NotificationTransportException.class, () -> adapter().botUsername());

        assertEquals("이 봇은 다른 연결 서비스를 사용 중입니다. 관리자에게 연결 설정 확인을 요청해 주세요.", error.getMessage());
        assertTrue(output.getOut().contains("hasWebhook=true pendingUpdateCount=2 acceptsMessage=false"));
        assertFalse(output.getAll().contains("https://existing.invalid"));
        assertFalse(output.getAll().contains("private-webhook"));
        assertFalse(output.getAll().contains("synthetic-secret"));
        assertFalse(output.getAll().contains("callback_query"));
        assertFalse(output.getAll().contains("synthetic-token"));
        server.verify();
    }

    @Test
    void emptyUpdateFilterAllowsMessagesAndMissingPendingCountStaysUnknown(CapturedOutput output) {
        server.expect(requestTo("https://telegram.invalid/botsynthetic-token/getWebhookInfo"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"url":"","allowed_updates":[]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://telegram.invalid/botsynthetic-token/getMe"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"ok":true,"result":{"username":"example_bot"}}
                        """, MediaType.APPLICATION_JSON));

        assertEquals("example_bot", adapter().botUsername());

        assertTrue(output.getOut().contains("hasWebhook=false pendingUpdateCount=UNKNOWN acceptsMessage=true"));
        assertFalse(output.getAll().contains("pendingUpdateCount=0"));
        server.verify();
    }

    @Test
    void upstreamFailureDoesNotExposeTokenOrRemoteResponse() {
        server.expect(requestTo("https://telegram.invalid/botsynthetic-token/getWebhookInfo"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"description\":\"synthetic upstream detail\"}"));

        var error = assertThrows(NotificationTransportException.class, () -> adapter().botUsername());

        assertEquals("텔레그램 연결 서버에 접속하지 못했습니다. 잠시 후 다시 시도해 주세요.", error.getMessage());
        assertFalse(error.getMessage().contains("synthetic"));
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void readsPrivateStartUpdateAndPreservesSenderAndMessageTime(CapturedOutput output) {
        server.expect(request -> {
            assertEquals("/botsynthetic-token/getUpdates", request.getURI().getPath());
            assertNull(request.getURI().getRawQuery());
        }).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"offset":123,"timeout":0,"limit":100,"allowed_updates":["message"]}
                        """))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":123,"message":{"message_id":7,
                        "from":{"id":456,"is_bot":false,"first_name":"Synthetic","language_code":"ko"},
                        "chat":{"id":456,"first_name":"Synthetic","type":"private"},
                        "date":1788840600,"text":"/start aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "entities":[{"offset":0,"length":6,"type":"bot_command"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var updates = adapter().updates(123);

        assertEquals(1, updates.size());
        assertEquals(123, updates.getFirst().updateId());
        assertEquals(456, updates.getFirst().message().from().id());
        assertFalse(updates.getFirst().message().from().bot());
        assertEquals(456, updates.getFirst().message().chat().id());
        assertEquals("private", updates.getFirst().message().chat().type());
        assertEquals(1788840600, updates.getFirst().message().date());
        assertEquals("/start " + "a".repeat(43), updates.getFirst().message().text());
        assertTrue(output.getOut().contains("expectedPath=true hasQuery=false isPost=true isJson=true"));
        assertFalse(output.getAll().contains("synthetic-token"));
        assertFalse(output.getAll().contains("telegram.invalid"));
        assertFalse(output.getAll().contains("/getUpdates"));
        server.verify();
    }

    @Test
    void requestBoundaryLogsOnlyBooleansOnceEvenWithExtraBasePathAndQuery(CapturedOutput output) {
        builder.baseUrl("https://telegram.invalid/private-prefix?private-query=synthetic-query");
        server.expect(times(2), request -> {
            assertEquals("/private-prefix/botsynthetic-token/getUpdates", request.getURI().getPath());
            assertEquals("private-query=synthetic-query", request.getURI().getRawQuery());
        }).andRespond(withSuccess("{\"ok\":true,\"result\":[]}", MediaType.APPLICATION_JSON));
        var adapter = adapter();

        adapter.updates(123);
        adapter.updates(123);

        assertTrue(output.getOut().contains("expectedPath=false hasQuery=true isPost=true isJson=true"));
        assertEquals(1, output.getOut().lines().filter(line -> line.contains("텔레그램 연결 수신 요청 형식.")).count());
        assertFalse(output.getAll().contains("telegram.invalid"));
        assertFalse(output.getAll().contains("synthetic-token"));
        assertFalse(output.getAll().contains("private-prefix"));
        assertFalse(output.getAll().contains("private-query"));
        assertFalse(output.getAll().contains("synthetic-query"));
        server.verify();
    }

    @Test
    void receptionConflictLogsOnlyHttpStatusWithoutBotOrMessageDetails(CapturedOutput output) {
        server.expect(request -> assertEquals("/botsynthetic-token/getUpdates", request.getURI().getPath()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"description\":\"synthetic-private-detail\"}"));

        assertThrows(NotificationTransportException.class, () -> adapter().updates(123));

        assertTrue(output.getOut().contains("httpStatus=409"));
        assertFalse(output.getAll().contains("synthetic-token"));
        assertFalse(output.getAll().contains("synthetic-private-detail"));
        assertFalse(output.getAll().contains("https://telegram.invalid"));
        server.verify();
    }

    private TelegramConnectionAdapter adapter() {
        var properties = new NotificationProperties();
        properties.getTelegram().setBotToken("synthetic-token");
        return new TelegramConnectionAdapter(builder.build(), properties);
    }
}
