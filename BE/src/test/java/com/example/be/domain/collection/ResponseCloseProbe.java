package com.example.be.domain.collection;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;

/**
 * 응답을 실제로 닫는지 센다.
 *
 * <p><b>{@code MockRestServiceServer}로는 이걸 볼 수 없다.</b> 거기서는 응답이 이미 메모리에 있어
 * 닫든 말든 결과가 같다. 하지만 실제 커넥션에서는 닫지 않은 응답이 풀로 돌아가지 못하고, 수집은 소스마다
 * 반복 호출이라 몇 바퀴만 돌아도 풀이 마른다(#35 리뷰 P1). 그래서 요청 팩토리를 직접 끼워
 * {@code close()} 호출을 센다.
 *
 * <p>세는 건 <b>만든 응답 수와 닫은 응답 수</b>다. 둘을 비교하면 재시도로 여러 번 부른 경우까지
 * 한 번에 걸린다 — 한 바퀴만 닫고 나머지를 흘리는 게 정확히 놓치기 쉬운 경우다.
 */
public final class ResponseCloseProbe implements ClientHttpRequestFactory {

    private final HttpStatusCode status;
    private final HttpHeaders headers = new HttpHeaders();
    private final byte[] body;

    private int created;
    private int closed;

    private ResponseCloseProbe(HttpStatusCode status, byte[] body) {
        this.status = status;
        this.body = body;
    }

    /** 본문 없는 응답. 304·4xx·5xx처럼 본문을 읽지 않고 빠져나가는 경로용. */
    public static ResponseCloseProbe responding(HttpStatusCode status) {
        return new ResponseCloseProbe(status, new byte[0]);
    }

    public static ResponseCloseProbe responding(HttpStatusCode status, MediaType contentType, byte[] body) {
        ResponseCloseProbe probe = new ResponseCloseProbe(status, body);
        probe.headers.setContentType(contentType);
        return probe;
    }

    public ResponseCloseProbe withHeader(String name, String value) {
        headers.set(name, value);
        return this;
    }

    public int created() {
        return created;
    }

    public int closed() {
        return closed;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
        MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
        MockClientHttpResponse response = new MockClientHttpResponse(body, status) {
            @Override
            public void close() {
                closed++;
                super.close();
            }
        };
        response.getHeaders().putAll(headers);
        created++;
        request.setResponse(response);
        return request;
    }

    @Override
    public String toString() {
        return "응답 " + status;
    }
}
