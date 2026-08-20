package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.collection.converter.CollectionRunConverter;
import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.domain.settings.service.LlmPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 수동 실행 요청을 받는다. <b>이 클래스에는 트랜잭션이 없다.</b>
 *
 * <p>생성은 {@link CollectionRunCreator}가 자기 트랜잭션에서 하고, 여기서는 그 앞뒤의 조회만 한다.
 * 경계를 이렇게 나눠야 유니크 위반으로 생성이 롤백된 뒤에 <b>새 트랜잭션으로</b> 이긴 실행을 읽을 수 있다.
 * 한 트랜잭션 안에서는 롤백 표시된 영속성 컨텍스트로 아무것도 조회할 수 없다(#31 A2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRunCommandServiceImpl implements CollectionRunCommandService {

    private final CollectionRunRepository runRepository;
    private final CollectionRunCreator runCreator;
    private final LlmPlanService planService;
    private final AgentQuotaService quotaService;

    @Override
    public CollectionRunStartResult startManualRun(CollectionRunReqDTO.Create request) {
        CollectionRunReqDTO.Create safeRequest = request == null ? new CollectionRunReqDTO.Create() : request;
        validateTopicIds(safeRequest.getTopicIds());
        String idempotencyKey = normalizeIdempotencyKey(safeRequest.getIdempotencyKey());
        AgentPlan plan = planService.resolveRunPlan(safeRequest.getPlan());

        return findInProgress(idempotencyKey)
                .orElseGet(() -> create(safeRequest, idempotencyKey, plan));
    }

    /**
     * 유니크 위반으로 졌으면 이긴 실행을 찾아 명세대로 200으로 돌려준다.
     *
     * <p>못 찾는 경우가 하나 있다 — 위반과 재조회 사이에 이긴 실행이 끝나 버린 때다. 그러면 키가 다시
     * 비었다는 뜻이라 "이미 실행 중"은 거짓이다. 한 번 더 만들어 본다. 그 사이에 또 누가 같은 키를
     * 집었다면 그때는 정말로 실행 중이므로 RUN409가 맞다.
     */
    private CollectionRunStartResult create(CollectionRunReqDTO.Create request,
                                            String idempotencyKey,
                                            AgentPlan plan) {
        quotaService.assertRunCanStart(plan);
        try {
            return runCreator.create(request, idempotencyKey, plan);
        } catch (DuplicatedIdempotencyKeyException first) {
            return findInProgress(idempotencyKey)
                    .orElseGet(() -> retryCreate(request, idempotencyKey, plan));
        }
    }

    private CollectionRunStartResult retryCreate(CollectionRunReqDTO.Create request,
                                                 String idempotencyKey,
                                                 AgentPlan plan) {
        log.info("같은 키의 실행이 재조회 전에 끝났다. 새 실행으로 다시 만든다. idempotencyKey={}", idempotencyKey);

        try {
            return runCreator.create(request, idempotencyKey, plan);
        } catch (DuplicatedIdempotencyKeyException second) {
            return findInProgress(idempotencyKey)
                    .orElseThrow(() -> new RunException(RunErrorCode.RUN_IN_PROGRESS));
        }
    }

    private Optional<CollectionRunStartResult> findInProgress(String idempotencyKey) {
        return runRepository.findInProgressByOptionalIdempotencyKey(idempotencyKey, RunStatus.IN_PROGRESS_STATUSES)
                .map(this::alreadyRunning);
    }

    private CollectionRunStartResult alreadyRunning(CollectionRun run) {
        return new CollectionRunStartResult(
                GeneralSuccessCode.COLLECTION_ALREADY_RUNNING,
                CollectionRunConverter.toAlreadyRunning(run));
    }

    private void validateTopicIds(List<Long> topicIds) {
        if (topicIds != null && topicIds.stream().anyMatch(id -> id == null)) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "topicIds에는 null을 넣을 수 없습니다.");
        }
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
}
