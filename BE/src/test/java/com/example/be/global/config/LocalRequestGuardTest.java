package com.example.be.global.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LocalRequestGuardTest {

    private final LocalRequestGuard guard = new LocalRequestGuard(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example", "localhost.attacker.example", "127.0.0.1.attacker.example",
            "192.168.1.10", "0.0.0.0"})
    void rejectsRebindingHostEvenWithSameOriginMetadata(String host) throws Exception {
        var request = request();
        request.setServerName(host);
        request.addHeader("Sec-Fetch-Site", "same-origin");
        assertRejected(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://attacker.example", "null", "http://localhost.attacker.example",
            "http://localhost@attacker.example", "http://attacker.example@localhost", "file://localhost",
            "http://localhost/path", "http://localhost?x=1", "http://localhost#fragment",
            "http://localhost:65536", "http://localhost:0", "invalid"})
    void rejectsUntrustedAndMalformedOrigins(String origin) throws Exception {
        var request = request();
        request.addHeader("Origin", origin);
        assertRejected(request);
    }

    @Test
    void rejectsCrossSiteSimplePostWithoutReadingBodyOrRunningController() throws Exception {
        var request = request();
        request.addHeader("Sec-Fetch-Site", "cross-site");
        request.setContentType("application/x-www-form-urlencoded");
        assertRejected(request);
    }

    @Test
    void rejectsCrossSiteReadAndLegacyBrowserReferer() throws Exception {
        var request = request();
        request.setMethod("GET");
        request.addHeader("Referer", "https://attacker.example/page");
        assertRejected(request);
    }

    @Test
    void forwardedHeadersCannotTurnRemoteCallerIntoLoopback() throws Exception {
        var request = request();
        request.setRemoteAddr("192.168.1.10");
        request.addHeader("Forwarded", "for=127.0.0.1;host=localhost");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Forwarded-Host", "localhost");
        assertRejected(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173", "http://[::1]:5173",
            "http://localhost:8080", "https://localhost"})
    void acceptsLocalViteProxyAndSwagger(String origin) throws Exception {
        var request = request();
        request.addHeader("Origin", origin);
        request.addHeader("Referer", origin + "/#/settings");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertAllowed(request);
    }

    @Test
    void acceptsHeaderlessLocalCommandLineAndHealthCheck() throws Exception {
        var request = request();
        request.setRequestURI("/actuator/health");
        request.setMethod("GET");
        assertAllowed(request);
    }

    @Test
    void acceptsLocalIpv6() throws Exception {
        var request = request();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        request.setServerName("[::1]");
        assertAllowed(request);
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/news/sources/1/robots-check");
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        return request;
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        guard.doFilter(request, response, (req, res) -> fail("Rejected request reached business logic"));
        assertEquals(400, response.getStatus());
        var json = new ObjectMapper().readTree(response.getContentAsString());
        assertFalse(json.get("isSuccess").asBoolean());
        assertEquals("COMMON400", json.get("code").asString());
        assertEquals("입력값 검증 실패입니다.", json.get("message").asString());
        assertEquals(0, json.get("result").size());
    }

    private void assertAllowed(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var reachedController = new AtomicBoolean();
        guard.doFilter(request, response, (req, res) -> reachedController.set(true));
        assertTrue(reachedController.get());
        assertEquals(200, response.getStatus());
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
    }
}
