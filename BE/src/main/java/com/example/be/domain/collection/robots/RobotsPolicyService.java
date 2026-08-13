package com.example.be.domain.collection.robots;

import com.example.be.domain.sources.entity.Source;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 소스의 robots.txt를 확인하고 결과를 소스에 남긴다.
 *
 * <p>수집 실행이 소스마다 부르고, 화면의 재확인 버튼도 같은 경로를 쓴다. 결과를 저장해 두는 이유는
 * 목록 화면이 소스마다 robots.txt를 다시 받지 않게 하기 위해서다.
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
    @Transactional
    public RobotsDecision check(Source source) {
        if (!source.respectsRobots()) {
            log.debug("robotsMode=ignore라 robots.txt를 확인하지 않는다. sourceId={}", source.getId());
            return RobotsDecision.skipped(source);
        }

        RobotsLookup lookup = robotsTxtClient.lookup(source.getUrlTemplate());
        LocalDateTime checkedAt = LocalDateTime.now();

        if (!lookup.resolved()) {
            // 못 받았다고 막지 않는다. 판단할 근거가 없다는 뜻이지 금지가 아니다.
            source.applyRobotsCheck(Source.ROBOTS_STATUS_UNKNOWN, checkedAt);
            return new RobotsDecision(true, Source.ROBOTS_STATUS_UNKNOWN, checkedAt,
                    lookup.robotsTxtUrl(), null, lookup.reason());
        }

        boolean allowed = lookup.allows(source.getUrlTemplate());
        String status = allowed ? Source.ROBOTS_STATUS_ALLOWED : Source.ROBOTS_STATUS_DISALLOWED;
        source.applyRobotsCheck(status, checkedAt);

        if (!allowed) {
            log.warn("robots.txt가 수집을 막는다. sourceId={} url={}", source.getId(), source.getUrlTemplate());
        }

        return new RobotsDecision(allowed, status, checkedAt, lookup.robotsTxtUrl(),
                lookup.rules().crawlDelay(), null);
    }
}
