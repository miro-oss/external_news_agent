package com.example.be.domain.analysis.agent.client;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentClientFailureClassificationTest {
    @ParameterizedTest
    @CsvSource({"application/json,{", "text/html,<html>invalid response</html>"})
    void receivedButUnreadableResponseIsNotAConnectionFailure(String contentType, String body) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:8088");
        var client = new AgentClient(builder, properties);
        server.expect(requestTo("http://127.0.0.1:8088/v1/feedback/review"))
                .andRespond(withSuccess(body, MediaType.parseMediaType(contentType)));

        var error = assertThrows(AgentClientException.class,
                () -> client.feedbackReview(new tools.jackson.databind.ObjectMapper().createObjectNode()));

        assertEquals("SCHEMA_VIOLATION", error.getCode());
        assertEquals(AgentClientException.TimeoutPhase.NONE, error.getTimeoutPhase());
        assertEquals(AgentClientException.ClientFailureKind.RESPONSE_DECODE, error.getClientFailureKind());
        assertNull(error.getUsage());
        assertFalse(error.getMessage().contains(body));
        server.verify();
    }

    @ParameterizedTest @MethodSource("networkFailures")
    void connectionDiagnosticsAreBoundedAndNeverCauseAnAutomaticRetry(IOException cause,
            AgentClientException.ClientFailureKind kind, AgentClientException.TimeoutPhase phase) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new AgentProperties();
        properties.setBaseUrl("http://127.0.0.1:8088");
        var client = new AgentClient(builder, properties);
        server.expect(requestTo("http://127.0.0.1:8088/v1/feedback/review"))
                .andRespond(request -> { throw new ResourceAccessException("PRIVATE_REQUEST", cause); });

        var error = assertThrows(AgentClientException.class,
                () -> client.feedbackReview(new tools.jackson.databind.ObjectMapper().createObjectNode()));

        assertEquals("PROVIDER_UNAVAILABLE", error.getCode());
        assertEquals(kind, error.getClientFailureKind());
        assertEquals(phase, error.getTimeoutPhase());
        assertNull(error.getUsage());
        assertFalse(error.getMessage().contains("PRIVATE"));
        server.verify();
    }

    static Stream<Arguments> networkFailures() {
        return Stream.of(
                Arguments.of(new HttpConnectTimeoutException("PRIVATE_CONNECT"),
                        AgentClientException.ClientFailureKind.CONNECT_TIMEOUT, AgentClientException.TimeoutPhase.CONNECT),
                Arguments.of(new SocketTimeoutException("PRIVATE_READ"),
                        AgentClientException.ClientFailureKind.READ_TIMEOUT, AgentClientException.TimeoutPhase.READ),
                Arguments.of(new UnknownHostException("PRIVATE_HOST"),
                        AgentClientException.ClientFailureKind.DNS_FAILURE, AgentClientException.TimeoutPhase.NONE),
                Arguments.of(new ConnectException("PRIVATE_CONNECTION"),
                        AgentClientException.ClientFailureKind.CONNECTION_FAILURE, AgentClientException.TimeoutPhase.NONE),
                Arguments.of(new SSLHandshakeException("PRIVATE_TLS"),
                        AgentClientException.ClientFailureKind.TLS_FAILURE, AgentClientException.TimeoutPhase.NONE),
                Arguments.of(new IOException("PRIVATE_IO"),
                        AgentClientException.ClientFailureKind.IO_FAILURE, AgentClientException.TimeoutPhase.NONE));
    }
}
