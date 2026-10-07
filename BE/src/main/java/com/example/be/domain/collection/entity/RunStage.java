package com.example.be.domain.collection.entity;

/** RUNNING 실행의 현재 처리 단계. 대기·종료 실행과 기존 단계 미상 실행에는 저장하지 않는다. */
public enum RunStage {
    COLLECTING,
    CLUSTERING,
    ANALYZING,
    INVESTIGATING,
    GENERATING_REPORT,
    FINALIZING
}
