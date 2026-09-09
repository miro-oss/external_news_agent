package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.config.NotificationConfiguration;
import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.global.config.RestClientFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramConnectionWireTest {

    @Test
    void realJdkClientSendsJsonAndMapsPrivateMessageFromLoopbackServer() throws Exception {
        var requestUri = new AtomicReference<URI>();
        var requestMethod = new AtomicReference<String>();
        var requestContentType = new AtomicReference<String>();
        var requestBody = new AtomicReference<byte[]>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUri.set(exchange.getRequestURI());
            requestMethod.set(exchange.getRequestMethod());
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = """
                    {"ok":true,"result":[{"update_id":321,"message":{"message_id":7,
                    "from":{"id":456,"is_bot":false,"first_name":"Synthetic"},
                    "chat":{"id":456,"type":"private"},"date":1788840600,
                    "text":"/start aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var properties = new NotificationProperties();
            properties.getTelegram().setBotToken("123456:synthetic_token");
            properties.getTelegram().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var configuration = new NotificationConfiguration(new RestClientFactory());
            var adapter = new TelegramConnectionAdapter(configuration.telegramRestClient(properties), properties);

            var updates = adapter.updates(0);

            assertNotNull(requestUri.get());
            assertEquals("/bot123456:synthetic_token/getUpdates", requestUri.get().getPath());
            assertEquals("/bot123456:synthetic_token/getUpdates", requestUri.get().getRawPath());
            assertNull(requestUri.get().getRawQuery());
            assertEquals("POST", requestMethod.get());
            assertEquals("application/json", requestContentType.get());
            var json = new ObjectMapper().readTree(requestBody.get());
            assertEquals(0, json.path("offset").asLong());
            assertEquals(0, json.path("timeout").asInt());
            assertEquals(100, json.path("limit").asInt());
            assertEquals(1, json.path("allowed_updates").size());
            assertEquals("message", json.path("allowed_updates").get(0).asString());
            assertEquals(4, json.size());
            assertEquals(1, updates.size());
            assertEquals(321, updates.getFirst().updateId());
            assertEquals("private", updates.getFirst().message().chat().type());
            assertEquals(456, updates.getFirst().message().from().id());
            assertFalse(updates.getFirst().message().from().bot());
            assertEquals("/start " + "a".repeat(43), updates.getFirst().message().text());
            assertEquals(1788840600, updates.getFirst().message().date());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realJdkClientPreservesTokenSeparatorForIdentityOnboardingAndDelivery() throws Exception {
        var requests = new CopyOnWriteArrayList<Request>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            requests.add(new Request(uri, exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestBody().readAllBytes()));
            // Assert the documented raw URL shape so changes in token encoding remain observable.
            String json = switch (uri.getRawPath()) {
                case "/bot123456:synthetic_token-with-dash/getWebhookInfo" -> "{\"ok\":true,\"result\":{\"url\":\"\"}}";
                case "/bot123456:synthetic_token-with-dash/getMe" -> "{\"ok\":true,\"result\":{\"username\":\"example_bot\"}}";
                case "/bot123456:synthetic_token-with-dash/getChat" -> "{\"ok\":true,\"result\":{\"id\":456,\"type\":\"private\"}}";
                case "/bot123456:synthetic_token-with-dash/sendMessage" -> "{\"ok\":true,\"result\":{\"message_id\":987}}";
                default -> null;
            };
            byte[] response = (json == null ? "{\"ok\":false}" : json).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(json == null ? 401 : 200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var properties = new NotificationProperties();
            properties.getTelegram().setBotToken("123456:synthetic_token-with-dash");
            properties.getTelegram().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new NotificationConfiguration(new RestClientFactory()).telegramRestClient(properties);
            var adapter = new TelegramConnectionAdapter(client, properties);
            var sender = new TelegramNotificationSender(properties, client);
            var channel = NotificationChannel.builder().config(Map.of()).build();

            assertEquals("example_bot", adapter.botUsername());
            assertTrue(sender.isOnboarded(channel, "456"));
            assertEquals("987", sender.send(channel, "456", null, "<b>테스트 보고서</b>"));

            assertEquals(List.of("GET", "GET", "GET", "POST"), requests.stream().map(Request::method).toList());
            assertEquals(List.of("getWebhookInfo", "getMe", "getChat", "sendMessage"), requests.stream()
                    .map(request -> request.uri().getRawPath().substring("/bot123456:synthetic_token-with-dash/".length())).toList());
            assertNull(requests.get(0).uri().getRawQuery());
            assertNull(requests.get(1).uri().getRawQuery());
            assertEquals("chat_id=456", requests.get(2).uri().getRawQuery());
            var sent = requests.get(3);
            assertNull(sent.uri().getRawQuery());
            assertEquals("application/json", sent.contentType());
            var body = new ObjectMapper().readTree(sent.body());
            assertEquals("456", body.path("chat_id").asString());
            assertEquals("<b>테스트 보고서</b>", body.path("text").asString());
            assertEquals("HTML", body.path("parse_mode").asString());
            assertTrue(body.path("disable_web_page_preview").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    private record Request(URI uri, String method, String contentType, byte[] body) { }
}
