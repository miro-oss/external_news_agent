package com.example.be.domain.collection.content;

/** Internal diagnostics only; persisted and public fetch statuses keep their existing contract. */
public enum ArticleContentFailureReason {
    NONE,
    UNKNOWN,
    INVALID_URL,
    OUTBOUND_POLICY,
    REDIRECT_REJECTED,
    HTTP_ACCESS_DENIED,
    HTTP_CLIENT_ERROR,
    HTTP_RATE_LIMITED,
    HTTP_SERVER_ERROR,
    TLS_VALIDATION,
    TIMEOUT,
    NETWORK_IO,
    BODY_TOO_LARGE,
    UNSUPPORTED_CONTENT_TYPE,
    BODY_NOT_FOUND,
    RESOURCE_INVALID,
    INTERRUPTED,
    UNEXPECTED_ERROR,
    ROBOTS_DISALLOWED;

    /** Only failures that can change within the same bounded request loop are retried. */
    public boolean retryable() {
        return this == HTTP_RATE_LIMITED || this == HTTP_SERVER_ERROR
                || this == TIMEOUT || this == NETWORK_IO;
    }
}
