package com.example.be.global.config;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicDnsResolverTest {

    @Test
    void tlsUsesTheRequestedHostnameOnThePinnedSocket() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        when(dns.resolve("news.example")).thenReturn(new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        Socket socket = mock(Socket.class);
        TlsSocketStrategy tls = mock(TlsSocketStrategy.class);
        when(tls.upgrade(eq(socket), eq("news.example"), eq(443), any(), any()))
                .thenReturn(mock(SSLSocket.class));
        var operator = new DefaultHttpClientConnectionOperator(proxy -> socket,
                DefaultSchemePortResolver.INSTANCE, new PublicDnsResolver(dns), scheme -> tls);

        operator.connect(mock(ManagedHttpClientConnection.class), new HttpHost("https", "news.example", 443),
                null, Timeout.ofSeconds(1), SocketConfig.DEFAULT, new BasicHttpContext());

        verify(tls).upgrade(eq(socket), eq("news.example"), eq(443), any(), any());
        verify(dns, times(1)).resolve("news.example");
    }

    @Test
    void actualConnectionOperatorUsesTheValidatedAddressWithoutResolvingAgain() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        InetAddress publicAddress = InetAddress.getByAddress("news.example", new byte[]{8, 8, 8, 8});
        InetAddress reboundAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        when(dns.resolve("news.example")).thenReturn(new InetAddress[]{publicAddress},
                new InetAddress[]{reboundAddress});
        Socket socket = mock(Socket.class);
        DetachedSocketFactory sockets = mock(DetachedSocketFactory.class);
        when(sockets.create(anyString(), any())).thenReturn(socket);
        var operator = new DefaultHttpClientConnectionOperator(sockets, DefaultSchemePortResolver.INSTANCE,
                new PublicDnsResolver(dns), scheme -> null);
        HttpHost host = new HttpHost("http", "news.example", 80);

        operator.connect(mock(ManagedHttpClientConnection.class), host, null, Timeout.ofSeconds(1),
                SocketConfig.DEFAULT, new BasicHttpContext());

        ArgumentCaptor<SocketAddress> target = ArgumentCaptor.forClass(SocketAddress.class);
        verify(socket).connect(target.capture(), eq(1000));
        InetSocketAddress connected = (InetSocketAddress) target.getValue();
        assertFalse(connected.isUnresolved());
        assertSame(publicAddress, connected.getAddress());
        verify(dns, times(1)).resolve("news.example");

        // A later connection receives the changed DNS answer and is blocked before a socket is created.
        assertThrows(UnknownHostException.class, () -> operator.connect(mock(ManagedHttpClientConnection.class),
                host, null, Timeout.ofSeconds(1), SocketConfig.DEFAULT, new BasicHttpContext()));
        verify(sockets, times(1)).create(anyString(), any());
        verify(socket, times(1)).connect(any(), anyInt());
    }

    @Test
    void rejectsMixedPublicAndPrivateAnswersRatherThanPickingOne() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        when(dns.resolve("news.example")).thenReturn(new InetAddress[]{InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("169.254.169.254")});

        assertThrows(UnknownHostException.class, () -> new PublicDnsResolver(dns).resolve("news.example", 443));
    }

    @Test
    void rejectsEmptyDnsAnswersRatherThanFallingBackToUnresolvedSockets() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        when(dns.resolve("news.example")).thenReturn(null, new InetAddress[0]);
        PublicDnsResolver resolver = new PublicDnsResolver(dns);

        assertThrows(UnknownHostException.class, () -> resolver.resolve("news.example", 443));
        assertThrows(UnknownHostException.class, () -> resolver.resolve("news.example", 443));
    }

    @Test
    void socketRejectsUnresolvedAndPrivateEndpointsBeforeConnecting() throws IOException {
        try (PublicAddressSocket socket = new PublicAddressSocket()) {
            assertThrows(IOException.class, () -> socket.connect(InetSocketAddress.createUnresolved("news.example", 80), 1));
            assertThrows(IOException.class, () -> socket.connect(new InetSocketAddress("127.0.0.1", 80), 1));
            assertFalse(socket.isConnected());
        }
    }
}
