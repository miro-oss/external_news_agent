package com.example.be.domain.collection.robots;

import com.example.be.domain.sources.entity.Source;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 소스의 robots.txt를 확인해 판정만 돌려준다.
 *
 * <p><b>여기서 저장하지 않는다.</b> robots.txt 조회는 HTTP라 트랜잭션 밖에서 끝나야 하고,
 * 결과를 소스에 적는 건 짧은 트랜잭션 안에서 호출부가 한다({@link RobotsDecision#applyTo(Source)}).
 * 예전에는 이 메서드가 저장까지 했는데, 그러면 호출부가 예외를 던지는 순간 저장이 함께 롤백된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RobotsPolicyService {

    private final RobotsTxtClient robotsTxtClient;

    /**
     * {@code crawl_policy.robotsMode}가 {@code ignore}면 확인하지 않는다. 확인하지 않았다는 사실을
     * {@code allowed}로 덮어쓰지 않도록 상태도 건드리지 않는다.
     */
    public RobotsDecision evaluate(Source source) {
        if (!source.respectsRobots()) {
            log.debug("robotsMode=ignore라 robots.txt를 확인하지 않는다. sourceId={}", source.getId());
            return RobotsDecision.skipped(source);
        }

        RobotsLookup lookup = robotsTxtClient.lookup(source.getUrlTemplate());
        LocalDateTime checkedAt = LocalDateTime.now();

        if (!lookup.resolved()) {
            // 못 받았다고 막지 않는다. 판단할 근거가 없다는 뜻이지 금지가 아니다.
            return new RobotsDecision(true, Source.ROBOTS_STATUS_UNKNOWN, checkedAt,
                    lookup.robotsTxtUrl(), null, lookup.reason());
        }

        boolean allowed = lookup.allows(source.getUrlTemplate());
        String status = allowed ? Source.ROBOTS_STATUS_ALLOWED : Source.ROBOTS_STATUS_DISALLOWED;

        if (!allowed) {
            log.warn("robots.txt가 수집을 막는다. sourceId={} url={}", source.getId(), source.getUrlTemplate());
        }

        return new RobotsDecision(allowed, status, checkedAt, lookup.robotsTxtUrl(),
                lookup.rules().crawlDelay(), null);
    }
}
