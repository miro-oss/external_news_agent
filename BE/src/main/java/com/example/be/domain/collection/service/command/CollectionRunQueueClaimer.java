package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.repository.CollectionDispatchLockRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CollectionRunQueueClaimer {
    private final CollectionDispatchLockRepository dispatchLockRepository;
    private final CollectionRunRepository runRepository;

    @Value("${news.collection.queue.max-concurrent:2}")
    private int maxConcurrent = 2;

    /** 선택과 상태 전이를 짧은 트랜잭션으로 끝낸 뒤 외부 수집은 잠금 밖에서 실행한다. */
    @Transactional
    public List<Long> claimAvailable() {
        dispatchLockRepository.lockDispatcher();
        int slots = Math.max(0, Math.max(1, Math.min(4, maxConcurrent)) - (int) runRepository.countByStatus(RunStatus.RUNNING));
        if (slots == 0) return List.of();

        Set<Long> reservedTopics = new HashSet<>(runRepository.findTopicIdsByRunStatus(RunStatus.RUNNING));
        List<Long> claimed = new ArrayList<>();
        int page = 0;
        // 대기 중인 앞 요청이 한 주제로 막혀 있어도 다른 주제는 실행할 수 있다.
        while (claimed.size() < slots) {
            List<Long> ids = runRepository.findQueueIds(RunStatus.PENDING, PageRequest.of(page++, 100));
            if (ids.isEmpty()) break;
            for (Long id : ids) {
                CollectionRun run = runRepository.findByIdForUpdate(id).orElse(null);
                if (run == null || run.getStatus() != RunStatus.PENDING) continue;
                Set<Long> topics = new HashSet<>(run.getItems().stream().map(item -> item.getTopic().getId()).toList());
                boolean blocked = topics.stream().anyMatch(reservedTopics::contains);
                // 같은 주제에서는 앞 요청의 순서를 지킨다.
                reservedTopics.addAll(topics);
                if (blocked) continue;
                LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
                run.start(now);
                if (run.getTriggerType() == com.example.be.domain.collection.entity.TriggerType.SCHEDULED) {
                    run.getItems().forEach(item -> item.getTopic().recordCollectionStartedAt(now));
                }
                claimed.add(id);
                if (claimed.size() == slots) break;
            }
            if (ids.size() < 100) break;
            // 상태를 바꿨다면 다음 주기에 처음부터 다시 조회해 offset 이동으로 요청을 건너뛰지 않는다.
            if (!claimed.isEmpty()) break;
        }
        return List.copyOf(claimed);
    }

    @Transactional
    public void returnToQueue(Long runId) {
        runRepository.findByIdForUpdate(runId).filter(run -> run.getStatus() == RunStatus.RUNNING)
                .ifPresent(CollectionRun::returnToQueue);
    }
}
