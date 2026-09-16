package com.example.be.global.config;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.util.Timeout;

import java.net.http.HttpClient;
import java.time.Duration;

/** Trusted configured services and untrusted public collection URLs use separate network boundaries. */
@Component
public class RestClientFactory {

    public RestClient.Builder create(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    public RestClient.Builder createPublic(Duration connectTimeout, Duration readTimeout) {
        return createPublic(connectTimeout, readTimeout, SystemDefaultDnsResolver.INSTANCE);
    }

    RestClient.Builder createPublic(Duration connectTimeout, Duration readTimeout, DnsResolver resolver) {
        var connections = new PoolingHttpClientConnectionManagerBuilder() {
            @Override
            protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver ports,
                    DnsResolver dns, TlsSocketStrategy tls) {
                // Socket receives the exact resolved InetAddress, never a hostname that can be resolved again.
                // Keep Apache's default TLS trust and hostname verification using the original request host.
                return new DefaultHttpClientConnectionOperator(proxy -> new PublicAddressSocket(),
                        ports, dns, scheme -> "https".equalsIgnoreCase(scheme) ? tls : null);
            }
        }.setDnsResolver(new PublicDnsResolver(resolver))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(readTimeout.toMillis())).build())
                .build();
        var client = HttpClients.custom()
                .setConnectionManager(connections)
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis())).build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(client))
                .requestInterceptor((request, body, execution) -> {
                    PublicDestinationPolicy.validate(request.getURI());
                    return execution.execute(request, body);
                });
    }
}
