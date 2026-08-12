package com.example.be.domain.collection.connector;

import com.example.be.domain.sources.entity.SearchProvider;

import java.util.List;

/**
 * SEARCH 소스의 url_template에 적힌 provider 키 하나를 실제 HTTP 호출로 옮기는 어댑터.
 *
 * <p>구현체는 어떤 이유로든 <b>예외를 던지지 않고 빈 목록을 돌려준다.</b> 키가 없는 개발 환경에서도
 * 앱이 뜨고 화면이 보여야 하고, 소스 하나가 죽었다고 수집 실행 전체가 멈추면 안 된다.
 */
public interface SearchConnector {

    SearchProvider provider();

    List<CollectedArticle> search(SearchQuery query);
}
