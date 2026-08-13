package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ★ #31 A1. 앱이 죽으면 그 실행은 아무도 닫아 주지 않는다. 남은 RUNNING은 주제 충돌 검사에 걸려
 * <b>그 주제의 다음 실행을 영구히 막는다</b> — 운영 중 한 번만 죽어도 수집이 멈춘다.
 */
@ExtendWith(MockitoExtension.class)
class CollectionRunReaperTest {

    @Mock
    private CollectionRunRepository runRepository;

    @Mock
    private CollectionResultWriter resultWriter;

    @InjectMocks
    private CollectionRunReaper reaper;

    @Test
    void reapClosesEveryRunLeftInProgress() {
        when(runRepository.findIdsByStatusIn(RunStatus.IN_PROGRESS_STATUSES)).thenReturn(List.of(41L, 42L));

        reaper.reapInterruptedRuns();

        verify(resultWriter).abortRun(eq(41L), eq(CollectionRunWarning.CODE_RUN_INTERRUPTED), anyString());
        verify(resultWriter).abortRun(eq(42L), eq(CollectionRunWarning.CODE_RUN_INTERRUPTED), anyString());
    }

    @Test
    void reapDoesNothingWhenNoRunIsLeftInProgress() {
        when(runRepository.findIdsByStatusIn(RunStatus.IN_PROGRESS_STATUSES)).thenReturn(List.of());

        reaper.reapInterruptedRuns();

        verifyNoInteractions(resultWriter);
    }

    /**
     * 하나가 터졌다고 멈추면 뒤의 실행들이 계속 막힌 채로 남는다. 각각 따로 닫는다.
     */
    @Test
    void reapKeepsClosingAfterOneRunFails() {
        when(runRepository.findIdsByStatusIn(RunStatus.IN_PROGRESS_STATUSES)).thenReturn(List.of(41L, 42L, 43L));
        doThrow(new IllegalStateException("닫을 수 없다"))
                .when(resultWriter).abortRun(eq(42L), anyString(), anyString());

        reaper.reapInterruptedRuns();

        verify(resultWriter).abortRun(eq(41L), anyString(), anyString());
        verify(resultWriter).abortRun(eq(43L), anyString(), anyString());
    }

    /**
     * 진행 중 상태 목록은 {@link RunStatus#IN_PROGRESS_STATUSES} 하나만 본다.
     * 여기에 끝난 실행이 섞이면 이력을 덮어쓴다.
     */
    @Test
    void reapOnlyLooksAtInProgressStatuses() {
        when(runRepository.findIdsByStatusIn(RunStatus.IN_PROGRESS_STATUSES)).thenReturn(List.of());

        reaper.reapInterruptedRuns();

        verify(runRepository).findIdsByStatusIn(RunStatus.IN_PROGRESS_STATUSES);
        verify(runRepository, never()).findAll();
        verify(resultWriter, never()).abortRun(any(), anyString(), anyString());
    }
}
