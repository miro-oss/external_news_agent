package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 기동 시 진행 중으로 남아 있는 실행을 닫는다.
 *
 * <p>실행 행은 RUNNING으로 커밋된 뒤 {@code @Async}로 수집이 돈다. 그 사이에 프로세스가 죽으면
 * <b>그 행을 닫아 줄 주체가 아무도 없다.</b> 남은 RUNNING 실행은 주제 충돌 검사에 걸려
 * <b>해당 주제의 다음 실행을 영구히 막는다</b> — 운영 중 한 번만 죽어도 그 주제 수집이 멈춘다.
 *
 * <p>새 JVM에는 도는 수집이 없으므로, 기동 시점에 진행 중인 실행은 <b>정의상 전부 유실된 것</b>이다.
 * 살아 있는 작업을 잘못 닫을 위험이 없다는 뜻이라, 시간 기준(heartbeat·timeout) 없이 상태만 보고 닫는다.
 *
 * <p>⚠️ 한 DB를 여러 인스턴스가 함께 쓰면 이 전제가 깨진다 — 뒤에 뜬 인스턴스가 앞의 인스턴스에서
 * <b>돌고 있는</b> 실행을 닫아 버린다. 로컬 단일 인스턴스 전제이며, 다중 인스턴스로 가면
 * 실행에 소유자와 heartbeat를 두고 만료된 것만 닫아야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionRunReaper {

    private final CollectionRunRepository runRepository;
    private final CollectionResultWriter resultWriter;

    /**
     * 웹 서버가 요청을 받기 시작한 직후에 돈다. 그 사이 짧은 창에 들어온 요청은 아직 남아 있는
     * 유실 실행 때문에 RUN409를 받을 수 있지만, 정리가 끝나면 다음 요청부터 정상이라 스스로 풀린다.
     * 이 창을 없애려면 기동 순서에 개입해야 하는데, 로컬 전용 PoC에서 그만한 값이 아니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reapInterruptedRuns() {
        List<Long> interruptedRunIds = runRepository.findIdsByStatusIn(RunStatus.IN_PROGRESS_STATUSES);
        if (interruptedRunIds.isEmpty()) {
            return;
        }

        log.warn("지난 기동에서 닫히지 않은 수집 실행을 정리한다. count={} runIds={}",
                interruptedRunIds.size(), interruptedRunIds);

        // 하나가 터져도 나머지는 닫아야 한다. 여기서 멈추면 그 주제들이 계속 막힌 채로 남는다.
        interruptedRunIds.forEach(this::close);
    }

    private void close(Long runId) {
        try {
            resultWriter.abortRun(runId, CollectionRunWarning.CODE_RUN_INTERRUPTED,
                    "실행 도중 애플리케이션이 종료되어 다음 기동에서 정리했습니다.");
            log.info("유실된 수집 실행을 닫았다. runId={}", runId);
        } catch (RuntimeException exception) {
            log.error("유실된 수집 실행을 닫지 못했다. 이 주제는 계속 막힌다. runId={}", runId, exception);
        }
    }
}
