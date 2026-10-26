package com.example.be.global.config;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Wire fixtures are loopback-only; production's independent public DNS/socket policy stays enabled. */
class PublicResponseGuardTest {

    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);
    private final ExecutorService serverWorkers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<CloseableHttpClient> clients = new ArrayList<>();
    private HttpServer server;
    private final CountDownLatch releaseBody = new CountDownLatch(1);

    @AfterEach
    void closeResources() throws IOException {
        releaseBody.countDown();
        clients.forEach(client -> {
            try { client.close(); } catch (IOException ignored) { }
        });
        if (server != null) server.stop(0);
        serverWorkers.shutdownNow();
        deadlines.shutdownNow();
    }

    @ParameterizedTest
    @CsvSource({"200,false", "200,true", "302,false", "500,false"})
    void earlyCloseAbortsUnfinishedBodiesAndReleasesTheOnlyPoolSlot(int status, boolean declaredLength)
            throws Exception {
        startServer();
        server.createContext("/untrusted", exchange -> {
            try {
                exchange.sendResponseHeaders(status, declaredLength ? 16 * 1024 * 1024 : 0);
                exchange.getResponseBody().write(new byte[4096]);
                exchange.getResponseBody().flush();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (IOException ignored) {
                // Client cancellation is the behavior under test.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/ok", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        RestClient client = client(Duration.ofSeconds(10));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            int observed = client.get().uri("/untrusted").exchange((request, response) -> {
                // Unknown-length success consumes a small prefix. Other paths reject on headers.
                if (status == 200 && !declaredLength) response.getBody().readNBytes(32);
                return response.getStatusCode().value();
            });
            assertEquals(status, observed);
            assertEquals("ok", client.get().uri("/ok").retrieve().body(String.class));
        });
        assertTrue(deadlines.getQueue().isEmpty(), "Closed exchanges must remove their deadline tasks");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void totalDeadlineStopsBytesArrivingFasterThanTheSocketTimeout(boolean compressed) throws Exception {
        startServer();
        server.createContext("/drip", exchange -> {
            try {
                if (compressed) exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, 0);
                OutputStream body = compressed
                        ? new GZIPOutputStream(exchange.getResponseBody(), true) : exchange.getResponseBody();
                while (!Thread.currentThread().isInterrupted()) {
                    body.write('x');
                    body.flush();
                    Thread.sleep(20);
                }
            } catch (IOException ignored) {
                // The deadline must close the socket even while bytes keep arriving.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        RestClient client = client(Duration.ofMillis(350));

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            RestClientException failure = assertThrows(RestClientException.class,
                    () -> client.get().uri("/drip").retrieve().body(String.class));
            assertInstanceOf(SocketTimeoutException.class, failure.getMostSpecificCause());
        });
        assertTrue(deadlines.getQueue().isEmpty());
    }

    @Test
    void completeBodiesReuseConnectionsAndCancelTheirDeadlines() throws Exception {
        startServer();
        List<Integer> ports = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/ok", exchange -> {
            ports.add(exchange.getRemoteAddress().getPort());
            byte[] body = "complete".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        RestClient client = client(Duration.ofSeconds(10));

        assertEquals("complete", client.get().uri("/ok").retrieve().body(String.class));
        assertEquals("complete", client.get().uri("/ok").retrieve().body(String.class));
        assertEquals(2, ports.size());
        assertEquals(ports.get(0), ports.get(1));
        assertTrue(deadlines.getQueue().isEmpty());
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        server.start();
    }

    private RestClient client(Duration totalTimeout) {
        deadlines.setRemoveOnCancelPolicy(true);
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
        var apache = HttpClients.custom().setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(250))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(250)).build())
                .addExecInterceptorFirst("public-response-guard", new PublicResponseGuard(deadlines, totalTimeout))
                .disableRedirectHandling().disableAutomaticRetries().build();
        clients.add(apache);
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(apache))
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
    }
}
