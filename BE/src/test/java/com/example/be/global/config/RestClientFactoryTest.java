package com.example.be.global.config;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RestClientFactoryTest {

    @Test
    void usesHttpOneWithoutUpgradeHeaders() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Headers> headers = new AtomicReference<>();
        server.createContext("/health", exchange -> {
            headers.set(exchange.getRequestHeaders());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

            new RestClientFactory()
                    .create(Duration.ofSeconds(1), Duration.ofSeconds(1))
                    .baseUrl(baseUrl)
                    .build()
                    .get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity();

            assertFalse(headers.get().containsKey("Upgrade"));
            assertFalse(headers.get().containsKey("HTTP2-Settings"));
        } finally {
            server.stop(0);
        }
    }
}
