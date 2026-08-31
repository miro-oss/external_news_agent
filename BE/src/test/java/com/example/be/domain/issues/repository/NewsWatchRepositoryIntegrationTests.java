package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class NewsWatchRepositoryIntegrationTests {

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private NewsIssueRepository issueRepository;

    @Autowired
    private NewsWatchRepository watchRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void locksEligibleWatchAndExcludesItDuringCooldown() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Topic topic = topicRepository.save(Topic.builder()
                .name("속보 watch 통합테스트 " + System.nanoTime())
                .batchSize(10).intervalMinutes(60).active(true).build());
        OffsetDateTime issueTime = now.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        NewsIssue issue = issueRepository.save(NewsIssue.builder()
                .title("HBM4 증설")
                .status(IssueStatus.EMERGING)
                .firstSeenAt(issueTime)
                .lastSeenAt(issueTime)
                .articleCount(1)
                .publisherCount(1)
                .independentContentCount(1)
                .topic(topic)
                .entities(List.of("HBM4"))
                .build());
        NewsWatch watch = watchRepository.save(NewsWatch.builder()
                .watchType(WatchType.BREAKING)
                .issue(issue)
                .expiresAt(now.plusHours(48))
                .active(true)
                .build());
        entityManager.flush();
        entityManager.clear();

        List<NewsWatch> eligible = watchRepository.findEligibleBreakingForNotification(issue.getId(), now);

        assertEquals(List.of(watch.getId()), eligible.stream().map(NewsWatch::getId).toList());
        eligible.getFirst().claimUntil(now.plusMinutes(30));
        entityManager.flush();
        entityManager.clear();
        assertTrue(watchRepository.findEligibleBreakingForNotification(
                issue.getId(), now.plusMinutes(29)).isEmpty());
    }
}
