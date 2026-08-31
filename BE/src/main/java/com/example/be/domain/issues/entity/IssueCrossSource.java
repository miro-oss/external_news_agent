package com.example.be.domain.issues.entity;

import java.util.List;

/** 같은 이슈의 출처별 합의·독점·충돌 관측값. */
public record IssueCrossSource(
        List<String> consensus,
        List<SoleSource> soleSource,
        List<Conflict> conflicts,
        List<String> missingStakeholders
) {

    public IssueCrossSource {
        consensus = consensus == null ? List.of() : List.copyOf(consensus);
        soleSource = soleSource == null ? List.of() : List.copyOf(soleSource);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        missingStakeholders = missingStakeholders == null ? List.of() : List.copyOf(missingStakeholders);
    }

    public static IssueCrossSource empty() {
        return new IssueCrossSource(List.of(), List.of(), List.of(), List.of());
    }

    public record SoleSource(Long articleId, String text) {
    }

    public record Conflict(List<Long> articleIds, String text) {

        public Conflict {
            articleIds = articleIds == null ? List.of() : List.copyOf(articleIds);
        }
    }
}
