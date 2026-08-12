package com.example.be.domain.collection.connector;

import com.example.be.domain.sources.entity.SearchProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SEARCH 소스의 url_template에 적힌 provider 키로 어댑터를 찾는다. M3의 수집 실행이 진입점으로 쓴다.
 */
@Component
public class SearchConnectorRegistry {

    private final Map<SearchProvider, SearchConnector> connectors;

    public SearchConnectorRegistry(List<SearchConnector> connectors) {
        this.connectors = new EnumMap<>(SearchProvider.class);
        connectors.forEach(this::register);
    }

    public Optional<SearchConnector> find(SearchProvider provider) {
        return provider == null ? Optional.empty() : Optional.ofNullable(connectors.get(provider));
    }

    private void register(SearchConnector connector) {
        SearchConnector previous = connectors.put(connector.provider(), connector);
        if (previous != null) {
            throw new IllegalStateException(
                    "provider %s에 커넥터가 둘 이상이다: %s, %s".formatted(
                            connector.provider(),
                            previous.getClass().getSimpleName(),
                            connector.getClass().getSimpleName()));
        }
    }
}
