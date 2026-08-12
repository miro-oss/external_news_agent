package com.example.be.domain.collection.connector;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 커넥터가 쓸 {@link RestClient.Builder}를 만든다.
 *
 * <p>Spring Boot의 RestClient 자동설정은 별도 모듈(spring-boot-starter-restclient)에 있고 지금 클래스패스에 없다.
 * {@code RestClient} 자체는 spring-web에 있으므로, 의존성을 늘리는 대신 빌더만 직접 정의한다.
 * 나중에 그 스타터를 넣더라도 Boot 쪽 빌더가 {@code @ConditionalOnMissingBean}이라 이 빈에 자리를 내준다.
 *
 * <p><b>prototype이어야 한다.</b> 빌더는 가변이고 커넥터마다 baseUrl을 다르게 잡는다. 싱글턴으로 두면
 * 마지막에 등록된 커넥터의 baseUrl이 나머지를 덮어쓴다.
 */
@Configuration
public class SearchConnectorHttpConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @Scope("prototype")
    public RestClient.Builder searchRestClientBuilder() {
        return RestClient.builder().requestFactory(requestFactory());
    }

    /**
     * 타임아웃이 없으면 외부 검색 API가 응답하지 않을 때 수집 실행 스레드가 그대로 매달린다.
     */
    private ClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return requestFactory;
    }
}
