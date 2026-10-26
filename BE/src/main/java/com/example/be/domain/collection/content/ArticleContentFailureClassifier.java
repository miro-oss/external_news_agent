package com.example.be.domain.collection.content;

import com.example.be.global.config.PublicDestinationPolicy;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Classifies exception types, never exception messages or publisher-specific text. */
public final class ArticleContentFailureClassifier {

    private static final int MAX_CAUSES = 128;

    private ArticleContentFailureClassifier() {
    }

    public static ArticleContentFailureReason classify(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return ArticleContentFailureReason.INTERRUPTED;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean policy = false;
        boolean tls = false;
        boolean timeout = false;
        boolean network = false;
        for (Throwable cause = failure; cause != null && seen.size() < MAX_CAUSES && seen.add(cause);
             cause = cause.getCause()) {
            // SocketTimeoutException is an InterruptedIOException, but does not cancel the caller.
            boolean timedOut = cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof ConnectionRequestTimeoutException;
            if (cause instanceof InterruptedException || cause instanceof ClosedByInterruptException
                    || (cause instanceof InterruptedIOException && !timedOut)) {
                return ArticleContentFailureReason.INTERRUPTED;
            }
            policy |= cause instanceof PublicDestinationPolicy.RejectedDestinationException;
            tls |= cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException
                    || cause instanceof CertificateException || cause instanceof CertPathBuilderException
                    || cause instanceof CertPathValidatorException;
            timeout |= timedOut;
            network |= cause instanceof IOException;
        }
        if (policy) {
            return ArticleContentFailureReason.OUTBOUND_POLICY;
        }
        if (tls) {
            return ArticleContentFailureReason.TLS_VALIDATION;
        }
        if (timeout) {
            return ArticleContentFailureReason.TIMEOUT;
        }
        return network ? ArticleContentFailureReason.NETWORK_IO : ArticleContentFailureReason.UNEXPECTED_ERROR;
    }
}
