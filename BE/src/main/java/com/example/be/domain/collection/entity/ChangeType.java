package com.example.be.domain.collection.entity;

/**
 * 한 실행에서 기사를 어떻게 관측했는지. 기사 자체의 속성이 아니라 <b>관측의 속성</b>이다 —
 * 같은 기사가 run 42에서는 NEW이고 run 43에서는 UPDATED일 수 있다.
 */
public enum ChangeType {

    NEW,
    UPDATED,

    /** 이미 있고 내용도 그대로였다. 명세의 필터에는 없지만 skippedCount를 이력에서 재현하려면 남겨야 한다. */
    UNCHANGED
}
