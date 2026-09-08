package com.example.be.domain.collection.service.command;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionRunQueueDispatcher {
    private final CollectionRunQueueClaimer claimer;
    private final CollectionRunAsyncService asyncService;

    @Scheduled(fixedDelayString = "${news.collection.queue.poll-interval-ms:1000}")
    public void dispatchAvailable() {
        for (Long runId : claimer.claimAvailable()) {
            try {
                asyncService.execute(runId);
            } catch (TaskRejectedException exception) {
                claimer.returnToQueue(runId);
                log.info("수집 작업을 대기열에서 다시 시도합니다. runId={}", runId);
            }
        }
    }
}
