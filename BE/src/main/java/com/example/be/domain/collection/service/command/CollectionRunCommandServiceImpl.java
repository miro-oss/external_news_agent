package com.example.be.domain.collection.service.command;

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
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CollectionRunCommandServiceImpl implements CollectionRunCommandService {

    private static final String ACTIVE_IDEMPOTENCY_KEY_INDEX = "UQ_RUN_ACTIVE_IDEMPOTENCY_KEY";

    private final TopicRepository topicRepository;
    private final CollectionRunRepository runRepository;
    private final CollectionRunItemRepository runItemRepository;
    private final CollectionRunAsyncService runAsyncService;

    @Override
    public CollectionRunStartResult startManualRun(CollectionRunReqDTO.Create request) {
        CollectionRunReqDTO.Create safeRequest = request == null ? new CollectionRunReqDTO.Create() : request;
        String idempotencyKey = normalizeIdempotencyKey(safeRequest.getIdempotencyKey());

        return runRepository.findInProgressByOptionalIdempotencyKey(idempotencyKey, RunStatus.IN_PROGRESS_STATUSES)
                .map(run -> new CollectionRunStartResult(
                        GeneralSuccessCode.COLLECTION_ALREADY_RUNNING,
                        CollectionRunConverter.toAlreadyRunning(run)))
                .orElseGet(() -> createRun(safeRequest, idempotencyKey));
    }

    private CollectionRunStartResult createRun(CollectionRunReqDTO.Create request, String idempotencyKey) {
        List<Long> requestedTopicIds = normalizeTopicIds(request.getTopicIds());
        List<TopicRepository.CollectionTarget> targets = requestedTopicIds.isEmpty()
                ? topicRepository.findActiveCollectionTargets()
                : topicRepository.findActiveCollectionTargetsByTopicIds(requestedTopicIds);

        if (targets.isEmpty()) {
            throw new RunException(RunErrorCode.NO_TARGET_COMBINATION);
        }

        List<Long> targetTopicIds = distinctTopicIds(targets);
        validateNoTopicConflict(targetTopicIds);

        CollectionRun run = CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey(idempotencyKey)
                .forceRefresh(Boolean.TRUE.equals(request.getForceRefresh()))
                .startedAt(LocalDateTime.now())
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
     * 같은 키가 거의 동시에 들어오면 앞의 조회로는 못 막는다 — 첫 요청이 아직 커밋되기 전이라 둘 다 통과한다.
     * 그래서 진행 중일 때만 유니크한 인덱스를 DB에 걸어 뒀는데(#21), 그 위반을 잡지 않으면 500이 나간다.
     *
     * <p>여기서 기존 실행을 다시 조회해 200으로 돌려주지는 못한다. 제약 위반이 난 시점에 영속성 컨텍스트가
     * 롤백 표시되기 때문이다. 지는 요청에게는 "이미 실행 중"이 사실이므로 RUN409로 알린다.
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
            return new RunException(RunErrorCode.RUN_IN_PROGRESS);
        }
        return exception;
    }

    private void validateNoTopicConflict(List<Long> targetTopicIds) {
        List<CollectionRun> conflicts =
                runRepository.findInProgressByTopicIds(targetTopicIds, RunStatus.IN_PROGRESS_STATUSES);
        if (conflicts.isEmpty()) {
            return;
        }

        CollectionRun conflict = conflicts.stream()
                .min(Comparator.comparing(CollectionRun::getId))
                .orElseThrow();
        List<Long> conflictTopicIds =
                runItemRepository.findTopicIdsByRunIdAndTopicIdIn(conflict.getId(), targetTopicIds);
        throw new RunException(RunErrorCode.RUN_IN_PROGRESS, Map.of(
                "conflictRunId", conflict.getId(),
                "conflictTopicIds", conflictTopicIds));
    }

    private List<Long> normalizeTopicIds(List<Long> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            return List.of();
        }
        if (topicIds.stream().anyMatch(id -> id == null)) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "topicIds에는 null을 넣을 수 없습니다.");
        }
        return new LinkedHashSet<>(topicIds).stream().toList();
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }

        String normalized = idempotencyKey.trim();
        if (normalized.length() > CollectionRun.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "idempotencyKey는 " + CollectionRun.MAX_IDEMPOTENCY_KEY_LENGTH + "자 이하여야 합니다.");
        }
        return normalized;
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
            runAsyncService.execute(runId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runAsyncService.execute(runId);
            }
        });
    }
}
