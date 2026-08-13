package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.robots.RobotsDecision;

/**
 * 조합 하나를 <b>바깥에서 읽어 온</b> 결과. 아직 아무것도 저장하지 않은 상태다.
 *
 * <p>HTTP 호출과 대기(크롤 간격, 백오프)를 트랜잭션 밖에서 끝내고 이 값만 짧은 트랜잭션에 넘긴다.
 * 외부 I/O를 트랜잭션 안에서 하면 크롤 간격 상한 30초 + 재시도 동안 DB 커넥션을 붙잡고 있게 된다.
 */
record CollectionOutcome(FetchResult fetch,
                         RobotsDecision robots,
                         boolean notModified,
                         String etag,
                         String lastModified,
                         boolean validatorsUpdated) {

    static CollectionOutcome blockedByRobots(RobotsDecision robots) {
        return new CollectionOutcome(FetchResult.ok(java.util.List.of()), robots, false, null, null, false);
    }

    static CollectionOutcome of(FetchResult fetch, RobotsDecision robots) {
        return new CollectionOutcome(fetch, robots, false, null, null, false);
    }
}
