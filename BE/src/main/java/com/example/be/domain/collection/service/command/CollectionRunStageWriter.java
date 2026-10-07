package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.RunStage;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CollectionRunStageWriter {

    private final CollectionRunRepository runRepository;

    /** 외부 호출이나 긴 처리 전에 별도 트랜잭션을 커밋해 폴링에서 현재 단계를 볼 수 있게 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateStage(Long runId, RunStage stage) {
        Objects.requireNonNull(stage, "실행 단계는 null일 수 없습니다.");
        return runRepository.updateRunningStage(runId, stage) == 1;
    }
}
