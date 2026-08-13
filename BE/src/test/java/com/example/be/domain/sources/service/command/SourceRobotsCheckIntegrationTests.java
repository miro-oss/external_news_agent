package com.example.be.domain.sources.service.command;

import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.exception.SourceException;
import com.example.be.domain.sources.exception.code.SourceErrorCode;
import com.example.be.domain.sources.repository.SourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 명세는 robots.txt 조회에 실패해도 "상태는 unknown으로 저장"하라고 적는다. 저장이 실제로 커밋되는지는
 * <b>롤백하지 않는 테스트에서만</b> 확인할 수 있어서 여기는 {@code @Transactional}을 붙이지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class SourceRobotsCheckIntegrationTests {

    @Autowired
    private SourceCommandService sourceCommandService;

    @Autowired
    private SourceRepository sourceRepository;

    @MockitoBean
    private RobotsTxtClient robotsTxtClient;

    private Long sourceId;

    @BeforeEach
    void setUp() {
        sourceId = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("robots 통합테스트 소스")
                .urlTemplate("https://example.com/robots-check-" + UUID.randomUUID())
                .country("KR")
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_UNKNOWN)
                .active(true)
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        sourceRepository.deleteById(sourceId);
    }

    @Test
    void savesAllowedStatus() {
        given(robotsTxtClient.lookup(anyString())).willReturn(
                RobotsLookup.fetched("https://example.com/robots.txt",
                        com.example.be.domain.collection.robots.RobotsRules.permitAll()));

        sourceCommandService.checkRobots(sourceId);

        Source reloaded = sourceRepository.findById(sourceId).orElseThrow();
        assertEquals(Source.ROBOTS_STATUS_ALLOWED, reloaded.getRobotsStatus());
        assertNotNull(reloaded.getRobotsCheckedAt());
    }

    /**
     * 예외가 롤백을 일으키면 방금 적은 unknown이 함께 사라진다. 그러면 목록 화면이 옛 값을 계속 보여준다.
     */
    @Test
    void keepsUnknownStatusEvenThoughItFailsWith502() {
        given(robotsTxtClient.lookup(anyString()))
                .willReturn(RobotsLookup.unknown("https://example.com/robots.txt", "CONNECT_TIMEOUT"));

        SourceException exception = assertThrows(SourceException.class,
                () -> sourceCommandService.checkRobots(sourceId));

        assertEquals(SourceErrorCode.ROBOTS_CHECK_FAILED, exception.getCode());

        Source reloaded = sourceRepository.findById(sourceId).orElseThrow();
        assertEquals(Source.ROBOTS_STATUS_UNKNOWN, reloaded.getRobotsStatus());
        assertNotNull(reloaded.getRobotsCheckedAt());
    }
}
