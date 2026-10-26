package com.example.be.global.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicDestinationPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd", "ftp://news.example/a", "https://user:pass@news.example/a",
            "http://localhost/a", "http://LOCALHOST./a", "http://news.local/a", "http://metadata.google.internal/a",
            "http://service/a", "http://service.svc/a", "http://router.home.arpa/a", "http://news.example:0/a",
            "http://127.0.0.1/a", "http://127.1/a", "http://2130706433/a", "http://0177.0.0.1/a",
            "http://10.1.2.3/a", "http://172.16.0.1/a", "http://172.31.255.255/a", "http://192.168.1.1/a",
            "http://169.254.169.254/a", "http://168.63.129.16/a", "http://100.100.100.200/a",
            "http://0.0.0.0/a", "http://192.0.0.8/a", "http://192.88.99.1/a",
            "http://198.18.0.1/a", "http://192.0.2.1/a", "http://198.51.100.1/a", "http://203.0.113.1/a",
            "http://224.0.0.1/a", "http://240.0.0.1/a", "http://[::]/a", "http://[::1]/a",
            "http://[::ffff:127.0.0.1]/a", "http://[::ffff:192.168.0.1]/a", "http://[fe80::1%25eth0]/a",
            "http://[fc00::1]/a", "http://[fd00::1]/a", "http://[fe80::1]/a", "http://[ff02::1]/a",
            "http://[64:ff9b::a00:1]/a", "http://[::ffff:0:a00:1]/a",
            "http://[2002:7f00:1::1]/a", "http://[2001:db8::1]/a"
    })
    void rejectsNonPublicDestinationsWithoutDns(String url) {
        assertThrows(IllegalArgumentException.class, () -> PublicDestinationPolicy.validate(URI.create(url)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.hankyung.com/article/1", "https://NEWS.EXAMPLE./a%2Fb?q=%2F",
            "http://8.8.8.8/a", "http://172.32.0.1/a", "http://100.128.0.1/a", "https://[2606:4700:4700::1111]/a",
            "https://[2001:4860:4860::8888]/a", "https://xn--bcher-kva.example/a", "https://news.example:8443/a"})
    void acceptsPublicHttpDestinationsWithoutDns(String url) {
        assertDoesNotThrow(() -> PublicDestinationPolicy.validate(URI.create(url)));
    }
}
