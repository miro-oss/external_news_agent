package com.example.be.global.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 복구 대상 설정의 존재로 복구 실행과 일반 백그라운드 작업을 서로 배제한다. */
public final class ReportRecoveryMode {

    public static final String RUN_IDS_PROPERTY = "news.reports.recovery.run-ids";

    private ReportRecoveryMode() {
    }

    /** 빈 값이나 잘못된 값도 복구 모드로 격리한 후 실행기의 입력 검증에서 거절한다. */
    public static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().containsProperty(RUN_IDS_PROPERTY);
        }
    }

    /** 복구 대상을 지정하지 않은 일반 기동에서만 백그라운드 작업을 허용한다. */
    public static final class Disabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !context.getEnvironment().containsProperty(RUN_IDS_PROPERTY);
        }
    }
}
