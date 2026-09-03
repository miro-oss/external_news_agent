package com.example.be.domain.collection.service.schedule;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.collection.service.command.CollectionRunCreator;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 활성 주제를 짧은 주기로 확인하고, 각 주제 설정에 따라 실행을 시작한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionScheduler {

    private final TopicRepository topicRepository;
    private final CollectionRunCreator runCreator;
    private final LlmPlanService planService;
    private final AgentQuotaService quotaService;

    @Scheduled(fixedDelayString = "${news.collection.scheduler.poll-interval-ms:60000}")
    public void startDueCollections() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        List<Long> dueTopicIds = topicRepository.findDueCollectionTopicIds(now);
        if (dueTopicIds.isEmpty()) {
            return;
        }

        AgentPlan plan = planService.resolveRunPlan(null);
        for (Long topicId : dueTopicIds) {
            try {
                quotaService.assertRunCanStart(plan);
                runCreator.createScheduled(topicId, plan, LocalDateTime.now(ApiTimeZone.ZONE));
            } catch (Exception exception) {
                // 한 주제의 설정/수집 실패가 다음 주제의 정기 수집을 막으면 안 된다.
                log.error("예약 수집 실행을 시작하지 못했다. topicId={}", topicId, exception);
            }
        }
    }
}
