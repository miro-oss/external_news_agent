package com.example.be.global.config;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.apache.hc.client5.http.DnsResolver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RestClientFactoryTest {

    private final RestClientFactory factory = new RestClientFactory();

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    @Test
    void publicFactoryBlocksLiteralLoopbackBeforeNetworkOrDns() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        var client = factory.createPublic(Duration.ofSeconds(1), Duration.ofSeconds(1), dns).build();

        assertThrows(IllegalArgumentException.class, () -> client.get().uri("http://127.0.0.1:1/robots.txt")
                .retrieve().toBodilessEntity());
        verifyNoInteractions(dns);
    }

    @Test
    void publicFactoryBlocksPrivateDnsAtItsActualTransport() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        when(dns.resolve("news.example")).thenReturn(new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var client = factory.createPublic(Duration.ofSeconds(1), Duration.ofSeconds(1), dns).build();

        assertThrows(org.springframework.web.client.RestClientException.class, () -> client.get()
                .uri("http://news.example:1/robots.txt").retrieve().toBodilessEntity());
        verify(dns, times(1)).resolve("news.example");
    }

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

            factory
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
