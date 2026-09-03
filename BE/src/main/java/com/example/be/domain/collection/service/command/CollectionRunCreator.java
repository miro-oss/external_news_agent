package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.collection.converter.CollectionRunConverter;
import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 실행 행을 만드는 트랜잭션. <b>이 클래스가 트랜잭션 경계다.</b>
 *
 * <p>{@link CollectionRunCommandServiceImpl}에서 떼어낸 이유는 유니크 위반을 복구하기 위해서다.
 * 제약 위반이 나면 그 트랜잭션의 영속성 컨텍스트가 롤백 표시되어 <b>같은 트랜잭션 안에서는 이긴 실행을
 * 다시 조회할 수 없다.</b> 생성이 별도 트랜잭션이면 롤백이 여기서 끝나고, 호출자가 새 트랜잭션으로
 * 기존 실행을 읽어 명세대로 200을 돌려줄 수 있다(#31 A2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRunCreator {

    private static final String ACTIVE_IDEMPOTENCY_KEY_INDEX = "UQ_RUN_ACTIVE_IDEMPOTENCY_KEY";

    private final TopicRepository topicRepository;
    private final CollectionRunRepository runRepository;
    private final CollectionRunItemRepository runItemRepository;
    private final CollectionRunAsyncService runAsyncService;
    private final CollectionResultWriter resultWriter;

    @Transactional
    public CollectionRunStartResult create(CollectionRunReqDTO.Create request,
                                           String idempotencyKey,
                                           AgentPlan llmPlan) {
        List<Long> requestedTopicIds = normalizeTopicIds(request.getTopicIds());
        List<TopicRepository.CollectionTarget> targets = requestedTopicIds.isEmpty()
                ? topicRepository.findActiveCollectionTargets()
                : topicRepository.findActiveCollectionTargetsByTopicIds(requestedTopicIds);

        if (targets.isEmpty()) {
            throw new RunException(RunErrorCode.NO_TARGET_COMBINATION);
        }

        List<Long> targetTopicIds = distinctTopicIds(targets);
        // 검사와 생성 사이에 다른 요청이 끼어들지 못하게 대상 주제를 먼저 잠근다.
        topicRepository.lockByIds(targetTopicIds);

        // 잠금을 잡은 뒤 키를 다시 본다. 앞의 조회는 먼저 들어온 요청이 커밋되기 전이었을 수 있다.
        // 대상 주제가 겹치면 여기서 걸리고, 겹치지 않으면 아래 유니크 인덱스가 잡는다.
        Optional<CollectionRun> alreadyRunning =
                runRepository.findInProgressByOptionalIdempotencyKey(idempotencyKey, RunStatus.IN_PROGRESS_STATUSES);
        if (alreadyRunning.isPresent()) {
            return new CollectionRunStartResult(
                    GeneralSuccessCode.COLLECTION_ALREADY_RUNNING,
                    CollectionRunConverter.toAlreadyRunning(alreadyRunning.get()));
        }

        validateNoTopicConflict(targetTopicIds);

        CollectionRun run = CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey(idempotencyKey)
                .forceRefresh(Boolean.TRUE.equals(request.getForceRefresh()))
                .llmPlan(llmPlan)
                .startedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build();

        targets.forEach(target -> run.addItem(CollectionRunItem.builder()
                .topic(target.getTopic())
                .source(target.getSource())
                .status(RunItemStatus.RUNNING)
                .build()));

        CollectionRun saved = saveRun(run);
        scheduleAfterCommit(saved.getId());

        return new CollectionRunStartResult(
                GeneralSuccessCode.COLLECTION_STARTED,
                CollectionRunConverter.toCreated(saved, targetTopicIds, targets.size()));
    }

    /**
     * 스케줄러용 단일 주제 실행 생성. 주제를 잠근 뒤 만료 여부를 확인해야 여러 인스턴스가 같은
     * 만료 주제를 동시에 발견해도 한 번만 실행된다.
     */
    @Transactional
    public boolean createScheduled(Long topicId, AgentPlan llmPlan, LocalDateTime now) {
        List<Topic> lockedTopics = topicRepository.lockByIds(List.of(topicId));
        if (lockedTopics.isEmpty() || !lockedTopics.getFirst().isCollectionDueAt(now)) {
            return false;
        }

        List<TopicRepository.CollectionTarget> targets =
                topicRepository.findActiveCollectionTargetsByTopicIds(List.of(topicId));
        if (targets.isEmpty()) {
            return false;
        }

        validateNoTopicConflict(List.of(topicId));

        CollectionRun run = CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.SCHEDULED)
                .forceRefresh(false)
                .llmPlan(llmPlan)
                .startedAt(now)
                .build();
        targets.forEach(target -> run.addItem(CollectionRunItem.builder()
                .topic(target.getTopic())
                .source(target.getSource())
                .status(RunItemStatus.RUNNING)
                .build()));

        CollectionRun saved = saveRun(run);
        lockedTopics.getFirst().recordCollectionStartedAt(now);
        scheduleAfterCommit(saved.getId());
        return true;
    }

    /**
     * 같은 키가 <b>다른 주제</b>로 거의 동시에 들어오면 주제 잠금이 겹치지 않아 둘 다 통과한다.
     * 그래서 진행 중일 때만 유니크한 인덱스를 DB에 걸어 뒀고(#21), 그 위반이 여기로 온다.
     *
     * <p>Oracle은 앞선 트랜잭션이 커밋될 때까지 이 insert를 대기시키므로, 예외가 나온 시점에는
     * 이긴 실행이 이미 커밋되어 있다. 호출자의 재조회가 그걸 찾는다.
     */
    private CollectionRun saveRun(CollectionRun run) {
        try {
            return runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicatedIdempotencyKey(exception);
        }
    }

    private RuntimeException translateDuplicatedIdempotencyKey(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause.getMessage();

        if (message != null && message.toUpperCase(Locale.ROOT).contains(ACTIVE_IDEMPOTENCY_KEY_INDEX)) {
            return new DuplicatedIdempotencyKeyException();
        }
        return exception;
    }

    private void validateNoTopicConflict(List<Long> targetTopicIds) {
        List<CollectionRun> conflicts =
                runRepository.findInProgressByTopicIds(targetTopicIds, RunStatus.IN_PROGRESS_STATUSES);
        if (conflicts.isEmpty()) {
            return;
        }

        // conflictRunId는 단수라 가장 먼저 시작한 실행을 대표로 내보내고,
        // conflictTopicIds는 충돌한 실행 전부에서 모은다.
        CollectionRun conflict = conflicts.stream()
                .min(Comparator.comparing(CollectionRun::getId))
                .orElseThrow();
        List<Long> conflictRunIds = conflicts.stream().map(CollectionRun::getId).toList();
        List<Long> conflictTopicIds =
                runItemRepository.findTopicIdsByRunIdInAndTopicIdIn(conflictRunIds, targetTopicIds);

        throw new RunException(RunErrorCode.RUN_IN_PROGRESS, Map.of(
                "conflictRunId", conflict.getId(),
                "conflictTopicIds", conflictTopicIds));
    }

    private List<Long> normalizeTopicIds(List<Long> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(topicIds).stream().toList();
    }

    private List<Long> distinctTopicIds(List<TopicRepository.CollectionTarget> targets) {
        return targets.stream()
                .map(target -> target.getTopic().getId())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
    }

    private void scheduleAfterCommit(Long runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(runId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(runId);
            }
        });
    }

    /**
     * 실행 행은 이미 RUNNING으로 커밋돼 있다. 스레드풀이 작업을 거절하면 아무도 그 행을 닫지 않아
     * 영원히 RUNNING으로 남고, 그 주제는 충돌 검사에 걸려 다시 실행할 수도 없게 된다.
     */
    private void dispatch(Long runId) {
        try {
            runAsyncService.execute(runId);
        } catch (TaskRejectedException exception) {
            log.error("수집 실행을 시작하지 못했다. 실행을 실패로 닫는다. runId={}", runId, exception);
            resultWriter.failRun(runId);
        }
    }
}
