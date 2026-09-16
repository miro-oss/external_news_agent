package com.example.be.domain.collection.content;

import com.example.be.global.config.PublicDestinationPolicy;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.stream.Stream;

import static com.example.be.domain.collection.content.ArticleContentFailureReason.*;
import static org.junit.jupiter.api.Assertions.*;

class ArticleContentFailureClassifierTest {

    @ParameterizedTest
    @MethodSource("transportFailures")
    void classifiesNestedCausesWithoutDependingOnMessages(Throwable cause, ArticleContentFailureReason expected) {
        Throwable wrapped = new ResourceAccessException("private URL and arbitrary transport text",
                new IOException("outer I/O wrapper", cause));

        assertEquals(expected, ArticleContentFailureClassifier.classify(wrapped));
    }

    private static Stream<Arguments> transportFailures() {
        return Stream.of(
                Arguments.of(new SSLHandshakeException("opaque"), TLS_VALIDATION),
                Arguments.of(new SSLPeerUnverifiedException("opaque"), TLS_VALIDATION),
                Arguments.of(new CertificateExpiredException("opaque"), TLS_VALIDATION),
                Arguments.of(new CertPathBuilderException("opaque"), TLS_VALIDATION),
                Arguments.of(new CertPathValidatorException("opaque"), TLS_VALIDATION),
                Arguments.of(new SocketTimeoutException("opaque"), TIMEOUT),
                Arguments.of(new HttpTimeoutException("opaque"), TIMEOUT),
                Arguments.of(new ConnectionRequestTimeoutException("opaque"), TIMEOUT),
                Arguments.of(new ConnectException("opaque"), NETWORK_IO),
                Arguments.of(new UnknownHostException("opaque"), NETWORK_IO),
                Arguments.of(new EOFException("opaque"), NETWORK_IO),
                Arguments.of(new InterruptedException("opaque"), INTERRUPTED),
                Arguments.of(new InterruptedIOException("opaque"), INTERRUPTED),
                Arguments.of(new ClosedByInterruptException(), INTERRUPTED));
    }

    @Test
    void destinationPolicyDenialIsPermanentEvenInsideIoAndTlsWrappers() {
        var rejected = assertThrows(PublicDestinationPolicy.RejectedDestinationException.class,
                () -> PublicDestinationPolicy.validate(URI.create("https://127.0.0.1/article")));
        var handshake = new SSLHandshakeException("opaque");
        handshake.initCause(rejected);

        assertEquals(OUTBOUND_POLICY, ArticleContentFailureClassifier.classify(
                new ResourceAccessException("opaque", handshake)));
        assertFalse(OUTBOUND_POLICY.retryable());
    }

    @Test
    void threadCancellationTakesPrecedenceAndDoesNotClearTheFlag() {
        try {
            Thread.currentThread().interrupt();
            assertEquals(INTERRUPTED, ArticleContentFailureClassifier.classify(new SocketTimeoutException()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unknownRuntimeErrorsAreNeverRetriedBasedOnTheirMessage() {
        assertEquals(UNEXPECTED_ERROR, ArticleContentFailureClassifier.classify(
                new IllegalStateException("timeout SSL handshake connection reset")));
        assertEquals(UNEXPECTED_ERROR, ArticleContentFailureClassifier.classify(null));
        assertFalse(UNEXPECTED_ERROR.retryable());
    }

    @Test
    void causeCyclesTerminateAndStillKeepTheRelevantFailure() {
        var outer = new IOException("opaque");
        var tls = new SSLHandshakeException("opaque");
        outer.initCause(tls);
        tls.initCause(outer);

        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertEquals(TLS_VALIDATION, ArticleContentFailureClassifier.classify(outer)));
    }

    @Test
    void onlyTemporaryReasonsAreRetryable() {
        var temporary = EnumSet.of(HTTP_RATE_LIMITED, HTTP_SERVER_ERROR, TIMEOUT, NETWORK_IO);
        for (ArticleContentFailureReason reason : ArticleContentFailureReason.values()) {
            assertEquals(temporary.contains(reason), reason.retryable(), reason.name());
        }
    }
}
