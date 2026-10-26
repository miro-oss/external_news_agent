package com.example.be.domain.collection.converter;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStage;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.service.query.CollectionRunCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CollectionRunConverterTest {

    private static final CollectionRunCoverage EMPTY_COVERAGE = new CollectionRunCoverage(
            0, 0, null, 0, 0, 0, null, 0, 0, null, 30);

    @ParameterizedTest
    @EnumSource(RunStage.class)
    void exposesEveryRunningStageInBothResponses(RunStage stage) {
        CollectionRun run = run(RunStatus.RUNNING, stage);

        assertEquals(stage.name(), CollectionRunConverter.toSummary(run, 0).getStage());
        assertEquals(stage.name(), CollectionRunConverter.toDetail(
                run, List.of(), List.of(), EMPTY_COVERAGE).getStage());
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void neverExposesStageForPendingOrTerminalRuns(RunStatus status) {
        CollectionRun run = run(status, RunStage.ANALYZING);

        assertNull(CollectionRunConverter.toSummary(run, 0).getStage());
        assertNull(CollectionRunConverter.toDetail(run, List.of(), List.of(), EMPTY_COVERAGE).getStage());
    }

    @Test
    void preservesUnknownStageForLegacyRunningRows() {
        CollectionRun run = run(RunStatus.RUNNING, null);

        assertNull(CollectionRunConverter.toSummary(run, 0).getStage());
        assertNull(CollectionRunConverter.toDetail(run, List.of(), List.of(), EMPTY_COVERAGE).getStage());
    }

    private CollectionRun run(RunStatus status, RunStage stage) {
        return CollectionRun.builder()
                .id(42L)
                .triggerType(TriggerType.MANUAL)
                .status(status)
                .stage(stage)
                .build();
    }
}
