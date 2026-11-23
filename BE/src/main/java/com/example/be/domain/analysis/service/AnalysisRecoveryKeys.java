package com.example.be.domain.analysis.service;

/** An explicitly authorized recovery is a new, bounded attempt; original accounting is immutable. */
public final class AnalysisRecoveryKeys {
    private AnalysisRecoveryKeys() { }

    public static String validate(String recoveryId) {
        if (recoveryId == null || !recoveryId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("복구 ID는 영문·숫자로 시작하는 1~64자의 영문·숫자·밑줄·하이픈이어야 합니다.");
        return recoveryId;
    }

    public static String article(long runId, long articleId, String recoveryId) {
        return "recovery:" + validate(recoveryId) + ":run:" + runId + ":article:" + articleId;
    }
}
