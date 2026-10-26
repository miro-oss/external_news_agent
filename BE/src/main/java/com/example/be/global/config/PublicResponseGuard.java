package com.example.be.global.config;

import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounds a public exchange's lifetime and aborts unread bodies instead of draining an untrusted peer. */
final class PublicResponseGuard implements ExecChainHandler {

    private final ScheduledExecutorService deadlines;
    private final Duration timeout;

    PublicResponseGuard(ScheduledExecutorService deadlines, Duration timeout) {
        this.deadlines = deadlines;
        this.timeout = timeout;
    }

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        Exchange exchange = new Exchange(scope);
        exchange.deadline = deadlines.schedule(exchange::expire, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            ClassicHttpResponse response = chain.proceed(request, scope);
            exchange.check();
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                exchange.finish();
            } else {
                response.setEntity(new HttpEntityWrapper(entity) {
                    private InputStream guarded;

                    @Override
                    public InputStream getContent() throws IOException {
                        if (guarded == null) {
                            guarded = new FilterInputStream(super.getContent()) {
                                private boolean eof;
                                private boolean closed;
                                private long consumed;

                                @Override
                                public int read() throws IOException {
                                    return readGuarded(() -> in.read(), true);
                                }

                                @Override
                                public int read(byte[] bytes, int offset, int length) throws IOException {
                                    return readGuarded(() -> in.read(bytes, offset, length), false);
                                }

                                private int readGuarded(Read read, boolean singleByte) throws IOException {
                                    exchange.check();
                                    try {
                                        int count = read.read();
                                        exchange.check();
                                        if (count >= 0) consumed += singleByte ? 1 : count;
                                        // Some Spring converters read exactly Content-Length without an EOF probe.
                                        // Decompressed entities report -1 and still require actual EOF.
                                        if (count == -1 || (entity.getContentLength() >= 0
                                                && consumed >= entity.getContentLength())) {
                                            eof = true;
                                            exchange.finish();
                                        }
                                        return count;
                                    } catch (IOException exception) {
                                        exchange.check();
                                        throw exception;
                                    }
                                }

                                @Override
                                public void close() throws IOException {
                                    if (closed) return;
                                    closed = true;
                                    exchange.finish();
                                    // Spring/Apache close normally drains to EOF to reuse a connection.
                                    // Drop the socket first when the consumer intentionally stopped early.
                                    if (!eof) scope.execRuntime.discardEndpoint();
                                    super.close();
                                }
                            };
                        }
                        return guarded;
                    }

                    @Override
                    public void writeTo(OutputStream output) throws IOException {
                        getContent().transferTo(output);
                    }

                    @Override
                    public void close() throws IOException {
                        getContent().close();
                    }
                });
            }
            return response;
        } catch (IOException | HttpException | RuntimeException exception) {
            exchange.finish();
            scope.execRuntime.discardEndpoint();
            exchange.check();
            throw exception;
        }
    }

    private static final class Exchange {
        // 0 active, 1 completed, 2 deadline expired. Completion must not erase an expired state.
        private final AtomicInteger state = new AtomicInteger();
        private final ExecChain.Scope scope;
        private ScheduledFuture<?> deadline;

        private Exchange(ExecChain.Scope scope) {
            this.scope = scope;
        }

        private void expire() {
            if (state.compareAndSet(0, 2)) {
                if (scope.originalRequest instanceof Cancellable cancellable) {
                    cancellable.cancel();
                }
                scope.execRuntime.discardEndpoint();
            }
        }

        private void finish() {
            state.compareAndSet(0, 1);
            deadline.cancel(false);
        }

        private void check() throws SocketTimeoutException {
            if (state.get() == 2) {
                throw new SocketTimeoutException("Public HTTP exchange exceeded its total time limit");
            }
        }
    }

    @FunctionalInterface
    private interface Read {
        int read() throws IOException;
    }
}
