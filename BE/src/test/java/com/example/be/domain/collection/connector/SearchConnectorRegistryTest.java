package com.example.be.domain.collection.connector;

import com.example.be.domain.sources.entity.SearchProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchConnectorRegistryTest {

    private final SearchConnector naver = new StubConnector(SearchProvider.NAVER);
    private final SearchConnector tavily = new StubConnector(SearchProvider.TAVILY);

    @Test
    void findsConnectorByProvider() {
        SearchConnectorRegistry registry = new SearchConnectorRegistry(List.of(naver, tavily));

        assertSame(naver, registry.find(SearchProvider.NAVER).orElseThrow());
        assertSame(tavily, registry.find(SearchProvider.TAVILY).orElseThrow());
    }

    @Test
    void returnsEmptyForUnregisteredProvider() {
        SearchConnectorRegistry registry = new SearchConnectorRegistry(List.of(naver));

        assertTrue(registry.find(SearchProvider.SERPAPI).isEmpty());
    }

    /**
     * url_template의 provider 키는 SearchProvider.fromKey()가 null을 돌려줄 수 있다. 조회하는 쪽에서 터지지 않게 한다.
     */
    @Test
    void returnsEmptyForNullProvider() {
        SearchConnectorRegistry registry = new SearchConnectorRegistry(List.of(naver));

        assertTrue(registry.find(null).isEmpty());
    }

    /**
     * 커넥터를 둘 등록하면 어느 쪽이 이길지 순서에 달리게 된다. 뜰 때 터뜨려서 조용히 다른 provider를 부르는 일을 막는다.
     */
    @Test
    void rejectsTwoConnectorsForOneProvider() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SearchConnectorRegistry(List.of(naver, new StubConnector(SearchProvider.NAVER))));

        assertTrue(exception.getMessage().contains(SearchProvider.NAVER.name()));
    }

    @Test
    void startsEmptyWhenNoConnectorIsRegistered() {
        SearchConnectorRegistry registry = new SearchConnectorRegistry(List.of());

        assertEquals(0, java.util.Arrays.stream(SearchProvider.values())
                .filter(provider -> registry.find(provider).isPresent())
                .count());
    }

    private record StubConnector(SearchProvider provider) implements SearchConnector {

        @Override
        public List<CollectedArticle> search(SearchQuery query) {
            return List.of();
        }
    }
}
