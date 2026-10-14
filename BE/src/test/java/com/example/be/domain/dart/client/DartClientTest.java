package com.example.be.domain.dart.client;

import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.dart.exception.DartUpstreamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class DartClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DartProperties properties = new DartProperties();

    @Test void checksStatusInsideHttp200AndNeverRetainsProviderDetails(CapturedOutput output) {
        server.expect(request -> assertEquals("/api/fnlttSinglAcntAll.json", request.getURI().getPath()))
                .andRespond(withSuccess("{\"status\":\"020\",\"message\":\"synthetic-sensitive-detail\"}", MediaType.APPLICATION_JSON));
        var error = assertThrows(DartUpstreamException.class, () -> client().financial("00126380", 2025, "11011", "CFS"));
        assertEquals("020", error.getStatus());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("synthetic"));
        assertFalse(output.getAll().contains("synthetic"));
        server.verify();
    }

    @Test void missingDataIsNotAnAuthenticationOrTransportFailure() {
        server.expect(request -> assertTrue(request.getURI().getRawQuery().contains("fs_div=CFS")))
                .andRespond(withSuccess("{\"status\":\"013\"}", MediaType.APPLICATION_JSON));
        assertEquals("013", client().financial("00126380", 2025, "11011", "CFS").path("status").asText());
        server.verify();
    }

    @Test void ignoresRawNetworkExceptionThatContainsCredentialAndUrl(CapturedOutput output) {
        server.expect(request -> { }).andRespond(withException(new IOException("synthetic-key-in-url-and-body")));
        var error = assertThrows(DartUpstreamException.class, () -> client().company("00126380"));
        assertEquals("NETWORK", error.getStatus());
        assertNull(error.getCause());
        assertFalse(output.getAll().contains("synthetic"));
        assertFalse(error.toString().contains("synthetic"));
    }

    @Test void rejectedHttpAndMalformedJsonUseSafeClassification() {
        server.expect(request -> { }).andRespond(withStatus(HttpStatus.FORBIDDEN).body("synthetic-sensitive-detail"));
        var error = assertThrows(DartUpstreamException.class, () -> client().company("00126380"));
        assertEquals("HTTP", error.getStatus());
        assertNull(error.getCause());
        server.verify();
        server.reset();
        server.expect(request -> { }).andRespond(withSuccess("{", MediaType.APPLICATION_JSON));
        assertEquals("BAD_RESPONSE", assertThrows(DartUpstreamException.class,
                () -> client().company("00126380")).getStatus());
    }

    @Test void refusesResponseWithoutExplicitDartStatus() {
        server.expect(request -> { }).andRespond(withSuccess("{\"list\":[]}", MediaType.APPLICATION_JSON));
        assertEquals("BAD_RESPONSE", assertThrows(DartUpstreamException.class,
                () -> client().company("00126380")).getStatus());
    }

    @Test void boundsResponseBeforeParsing() {
        server.expect(request -> { }).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON)
                .header("Content-Length", Integer.toString(DartClient.MAX_JSON_BYTES + 1)));
        assertEquals("BAD_RESPONSE", assertThrows(DartUpstreamException.class,
                () -> client().company("00126380")).getStatus());
    }

    @Test void keylessOrExplicitlyDisabledConfigurationMakesNoRequest() {
        var client = new DartClient(properties, builder.build());
        assertFalse(properties.isEnabled());
        assertEquals("DISABLED", assertThrows(DartUpstreamException.class, () -> client.company("00126380")).getStatus());
        properties.setApiKey("synthetic-test-key");
        properties.setEnabled(false);
        assertFalse(properties.isEnabled());
        server.verify();
    }

    private DartClient client() {
        properties.setApiKey("synthetic-test-key");
        return new DartClient(properties, builder.build());
    }
}
